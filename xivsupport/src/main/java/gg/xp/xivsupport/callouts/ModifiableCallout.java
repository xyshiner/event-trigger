package gg.xp.xivsupport.callouts;

import gg.xp.reevent.events.BaseEvent;
import gg.xp.reevent.time.TimeUtils;
import gg.xp.xivdata.data.ActionLibrary;
import gg.xp.xivdata.data.StatusEffectLibrary;
import gg.xp.xivsupport.events.actlines.events.HasAbility;
import gg.xp.xivsupport.events.actlines.events.HasDuration;
import gg.xp.xivsupport.events.actlines.events.HasStatusEffect;
import gg.xp.xivsupport.events.actlines.events.NameIdPair;
import gg.xp.xivsupport.gui.tables.renderers.IconTextRenderer;
import gg.xp.xivsupport.gui.tables.renderers.RenderUtils;
import gg.xp.xivsupport.models.XivCombatant;
import gg.xp.xivsupport.speech.BaseCalloutEvent;
import gg.xp.xivsupport.speech.BasicCalloutEvent;
import gg.xp.xivsupport.speech.CalloutEvent;
import gg.xp.xivsupport.speech.DynamicCalloutEvent;
import gg.xp.xivsupport.speech.ParentedCalloutEvent;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// TODO: refactor all of this into a builder pattern

/**
 * A callout that can be customized by the user on the UI. By default, there will be
 * TTS and on-screen-text, as well as optional graphics. The user can modify the tts and text,
 * independently, and change the color of the on-screen text.
 * <p>
 * Due to evolution of this class over time, some properties are defined in the constructors,
 * while others are set via a builder pattern. In addition, there are some static methods for
 * common patterns.
 *
 * @param <X> The type of event the callout handles. If the callout is typically called without
 *            an event, just define the field as {@code ModifiableCallout<?>}.
 */
public class ModifiableCallout<X> {

	private static final Logger log = LoggerFactory.getLogger(ModifiableCallout.class);

	private static final Pattern replacer = Pattern.compile("\\{(.+?)}");
	private final Map<String, Script> scriptCache = new ConcurrentHashMap<>();
	// TODO: should this use GroovyManager instead?
	private final GroovyShell interpreter = new GroovyShell();

	private final String description;
	private final String defaultTtsText;
	private final String defaultVisualText;
	private final Predicate<X> expiry;
	private final long defaultVisualHangTime;
	private final Object interpLock = new Object();
	private int errorCount;
	private static final int maxErrors = 10;
	private Function<? super X, ? extends @Nullable Component> guiProvider = e -> null;

	private static final Duration defaultHangDuration = Duration.of(5, ChronoUnit.SECONDS);

	private volatile ModifiedCalloutHandle handle;

	/**
	 * The most basic type of callout. Uses the same text for TTS and on-screen.
	 * The on-screen text will appear for the default time (5 seconds)
	 *
	 * @param description A description for the callout to be shown to the user.
	 * @param text        The default TTS and on-screen text.
	 */
	public ModifiableCallout(String description, String text) {
		this(description, text, 5000);
	}

	/**
	 * A callout with (optionally) different TTS and on-screen text, and a custom expiry condition.
	 * This callout will appear until the 'expiry' predicate returns true.
	 *
	 * @param description A description for the callout to be shown to the user.
	 * @param tts         The default TTS
	 * @param text        The default on-screen text
	 * @param expiry      A condition for expiring the callout (removes it from the on-screen display)
	 */
	public ModifiableCallout(String description, String tts, String text, Predicate<X> expiry) {
		this.description = description;
		this.defaultTtsText = tts;
		this.defaultVisualText = text;
		this.expiry = expiry;
		this.defaultVisualHangTime = 5000L;
	}

	/**
	 * A callout with the same TTS and on-screen text, and a custom expiry time.
	 *
	 * @param description A description for the callout to be shown to the user.
	 * @param ttsAndText  The default TTS and text.
	 * @param msExpiry    The time for the callout to be displayed on the screen.
	 */
	public ModifiableCallout(String description, String ttsAndText, int msExpiry) {
		this.description = description;
		this.defaultTtsText = ttsAndText;
		this.defaultVisualText = ttsAndText;
		this.expiry = expiresIn(Duration.ofMillis(msExpiry));
		this.defaultVisualHangTime = msExpiry;
	}

	public ModifiableCallout(String description, String tts, String text) {
		this.description = description;
		defaultTtsText = tts;
		defaultVisualText = text;
		defaultVisualHangTime = 5000L;
		this.expiry = expiresIn(defaultHangDuration);
	}

	/**
	 * Read-made predicates for expiration based on time since the event occurred
	 *
	 * @param dur The duration
	 * @param <X> The event type
	 * @return The predicate
	 */
	public static <X> Predicate<X> expiresIn(Duration dur) {
		Instant defaultExpiryAt = TimeUtils.now().plus(dur);
		return eventItem -> {
			if (eventItem instanceof BaseEvent be) {
				return be.getEffectiveTimeSince().compareTo(dur) > 0;
			}
			else {
				return defaultExpiryAt.isBefore(Instant.now());
			}
		};
	}

	/**
	 * Adds an automatic icon to a callout. This expects the event type to be a {@link HasAbility} or {@link HasStatusEffect}
	 *
	 * @return this (builder pattern)
	 */
	public ModifiableCallout<X> autoIcon() {
		this.guiProvider = e -> IconTextRenderer.getStretchyIcon(RenderUtils.guessIconFor(e));
		return this;
	}

	/**
	 * Adds a specific status icon to a callout.
	 *
	 * @param statusId The status effect ID
	 * @return this (builder pattern)
	 */
	public ModifiableCallout<X> statusIcon(long statusId) {
		this.guiProvider = e -> IconTextRenderer.getStretchyIcon(StatusEffectLibrary.iconForId(statusId, 0));
		return this;
	}

	/**
	 * Adds a specific ability icon to a callout.
	 *
	 * @param abilityId The ability ID
	 * @return this (builder pattern)
	 */
	public ModifiableCallout<X> abilityIcon(long abilityId) {
		this.guiProvider = e -> IconTextRenderer.getStretchyIcon(ActionLibrary.iconForId(abilityId));
		return this;
	}

	/**
	 * Adds a custom graphical component to a callout
	 *
	 * @param guiProvider A function to turn an event into an AWT component
	 * @return this (builder pattern)
	 */
	public ModifiableCallout<X> guiProvider(Function<? super X, ? extends Component> guiProvider) {
		this.guiProvider = guiProvider;
		return this;
	}

	/**
	 * Like {@link #expiresIn(Duration)} but takes a number of seconds rather than a Duration
	 *
	 * @param seconds Duration in seconds
	 * @param <X>     the event type
	 * @return the predicate
	 */
	public static <X> Predicate<X> expiresIn(int seconds) {
		return expiresIn(Duration.ofSeconds(seconds));
	}

	/**
	 * Should not be called by a trigger developer. This is used to attach customizations, since
	 * this object will not have access to them initially.
	 *
	 * @param handle The ModifiedCalloutHandle
	 */
	public void attachHandle(ModifiedCalloutHandle handle) {
		this.handle = handle;
	}

	/**
	 * @return The description for the callout
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * @return The default TTS
	 */
	public String getOriginalTts() {
		return defaultTtsText;
	}

	/**
	 * @return The default on-screen-text
	 */
	public String getOriginalVisualText() {
		return defaultVisualText;
	}

	/**
	 * Generate a real callout event based on this callout's settings, without any event or parameters.
	 *
	 * @return A CalloutEvent
	 */
	public CalloutEvent getModified() {
		return getModified(Collections.emptyMap());
	}

	/**
	 * Generate a real callout event based on this callout's settings, with an event to base it on.
	 *
	 * @param event The event that 'caused' this callout
	 * @return A CalloutEvent
	 */
	public CalloutEvent getModified(X event) {
		return getModified(event, Collections.emptyMap());
	}

	/**
	 * Generate a real callout event based on this callout's settings, with an event to base it on,
	 * plus additional variables.
	 *
	 * @param event        The event that 'caused' this callout
	 * @param rawArguments Additional variables to be passed to Groovy expressions
	 * @return A CalloutEvent
	 */
	public CalloutEvent getModified(X event, Map<String, Object> rawArguments) {
		// TODO
		Binding arguments = new Binding(new HashMap<>(rawArguments));
		arguments.setVariable("event", event);
		String callText;
		String visualText;
		if (handle == null) {
			// TODO: consider splitting out some logic here so that we can make these easily without worrying about handles
			log.trace("ModifiableCallout does not have handle yet ({})", description);
			callText = defaultTtsText;
			visualText = defaultVisualText;
		}
		else {
			callText = handle.getEffectiveTts();
			visualText = handle.getEffectiveText();
		}
		String modifiedCallText = applyReplacements(callText, arguments);
		String modifiedVisualText = applyReplacements(visualText, arguments);
		BaseCalloutEvent call;
		if (Objects.equals(modifiedVisualText, visualText) && this.expiry == null) {
			call = new BasicCalloutEvent(
					modifiedCallText,
					modifiedVisualText);
		}
		else {
			call = new ParentedCalloutEvent<>(
					event,
					modifiedCallText,
					() -> applyReplacements(visualText, arguments),
					expiry,
					guiProvider);
		}
		if (handle != null) {
			call.setColorOverride(handle.getTextColorOverride().get());
		}
		return call;
	}

	/**
	 * Generate a real callout event based on this callout's settings, without an event to base it on,
	 * but with additional variables.
	 *
	 * @param rawArguments Additional variables to be passed to Groovy expressions
	 * @return A CalloutEvent
	 */
	public CalloutEvent getModified(Map<String, Object> rawArguments) {
		String callText;
		String visualText;
		if (handle == null) {
			log.warn("ModifiableCallout does not have handle yet ({})", description);
			callText = defaultTtsText;
			visualText = defaultVisualText;
		}
		else {
			callText = handle.getEffectiveTts();
			visualText = handle.getEffectiveText();
		}
		Binding arguments = new Binding(new HashMap<>(rawArguments));
		String modifiedCallText = applyReplacements(callText, arguments);
		String modifiedVisualText = applyReplacements(visualText, arguments);
		BaseCalloutEvent call;
		if (Objects.equals(modifiedVisualText, visualText)) {
			call = new BasicCalloutEvent(
					modifiedCallText,
					modifiedVisualText);
		}
		else {
			call = new DynamicCalloutEvent(
					modifiedCallText,
					() -> applyReplacements(visualText, arguments),
					defaultVisualHangTime
			);
		}
		if (handle != null) {
			call.setColorOverride(handle.getTextColorOverride().get());
		}
		return call;
	}

	private boolean shouldLogError() {
		errorCount++;
		if (errorCount < maxErrors) {
			return true;
		}
		else if (errorCount == maxErrors) {
			log.error("Hit the maximum number of logged errors for ModifiableCallout '{}', silencing future errors", description);
			return true;
		}
		else {
			return false;
		}
	}

	private Script compile(String input) {
		return interpreter.parse(input);
	}

	@Contract("null, _ -> null")
	public @Nullable String applyReplacements(@Nullable String input, Binding binding) {
		if (input == null) {
			return null;
		}
		if (!input.contains("{")) {
			return input;
		}
		synchronized (interpLock) {
			return replacer.matcher(input).replaceAll(m -> {
				try {
					Script script = scriptCache.computeIfAbsent(m.group(1), this::compile);
					script.setBinding(binding);
					Object rawEval = script.run();
					if (rawEval == null) {
						return "null";
//						return m.group(0);
					}
					return singleReplacement(rawEval);
				}
				catch (Throwable e) {
					if (shouldLogError()) {
						log.error("Eval error for input '{}'", input, e);
					}
					return "Error";
				}
			});
		}
	}

	// Default conversions
	@SuppressWarnings("unused")
	public static String singleReplacement(Object rawValue) {
		String value;
		if (rawValue instanceof String strVal) {
			value = strVal;
		}
		else if (rawValue instanceof XivCombatant cbt) {
			if (cbt.isThePlayer()) {
				value = "YOU";
			}
			else {
				value = cbt.getName();
			}
		}
		else if (rawValue instanceof NameIdPair pair) {
			return pair.getName();
		}
		else if (rawValue instanceof Duration dur) {
			if (dur.isZero()) {
				return "NOW";
			}
			return String.format("%.01f", dur.toMillis() / 1000.0);
		}
		else if (rawValue instanceof Supplier supp) {
			Object realValue = supp.get();
			// Prevent infinite loops if a supplier produces another supplier
			if (realValue instanceof Supplier) {
				return realValue.toString();
			}
			else {
				return singleReplacement(realValue);
			}
		}
		else {
			value = rawValue.toString();
		}
		return value;
	}


	/**
	 * Used for things like water stack in TEA or P2S where the callout is based on a buff time or castbar.
	 * <p>
	 * Just because something *can* be used with this method doesn't mean it should - many buff/castbar mechanics
	 * do not warrant this. e.g. if the initial cast merely tells you what you need to do, or if it is expected
	 * that the buff will.
	 *
	 * @param desc The description.
	 * @param text The base text. For the visual text, the duration will be appended in parenthesis.
	 *             e.g. "Water on You" will become "Water on You" (123.4) will be appended, and the timer will count
	 *             down.
	 * @return the ModifiableCallout
	 */
	public static <Y extends HasDuration> ModifiableCallout<Y> durationBasedCall(String desc, String text) {
		Predicate<Y> expiry = hd -> hd.getEstimatedTimeSinceExpiry().compareTo(defaultLingerTime) > 0;
		return new ModifiableCallout<>(desc, text, text + " ({event.getEstimatedRemainingDuration()})", expiry);
	}

	public static <Y extends HasDuration> ModifiableCallout<Y> durationBasedCallWithoutDurationText(String desc, String text) {
		return new ModifiableCallout<>(desc, text, text, hd -> hd.getEstimatedTimeSinceExpiry().compareTo(defaultLingerTime) > 0);
	}

	private static final Duration defaultLingerTime = Duration.of(3, ChronoUnit.SECONDS);

	public static <Y extends HasDuration> Predicate<Y> durationExpiryPlusDefaultLinger() {
		return durationExpiryPlusLingerTime(defaultLingerTime);
	}

	public static <Y extends HasDuration> Predicate<Y> durationExpiry() {
		return durationExpiryPlusLingerTime(Duration.ZERO);
	}

	public static <Y extends HasDuration> Predicate<Y> durationExpiryPlusLingerTime(Duration linger) {
		return hd -> hd.getEstimatedTimeSinceExpiry().compareTo(linger) > 0;

	}

}
