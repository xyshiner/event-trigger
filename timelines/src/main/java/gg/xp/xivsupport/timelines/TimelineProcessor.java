package gg.xp.xivsupport.timelines;

import gg.xp.xivdata.data.*;
import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.HasDuration;
import gg.xp.xivsupport.gui.overlay.RefreshLoop;
import gg.xp.xivsupport.persistence.settings.BooleanSetting;
import gg.xp.xivsupport.persistence.settings.IntSetting;
import gg.xp.xivsupport.timelines.intl.LanguageReplacements;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TimelineProcessor {

	// TODO: we can use in/out of combat now

	private static final Logger log = LoggerFactory.getLogger(TimelineProcessor.class);
	private final List<TimelineEntry> entries;
	private final TimelineManager manager;
	private final List<TimelineEntry> rawEntries;
	private final IntSetting secondsFuture;
	private final IntSetting secondsPast;
	private final BooleanSetting debugMode;
	private final BooleanSetting showPrePull;
	private final RefreshLoop<TimelineProcessor> refresher;
	private @Nullable TimelineSync lastSync;

	record TimelineSync(ACTLogLineEvent line, double lastSyncTime, TimelineEntry original) {
	}

	private TimelineProcessor(TimelineManager manager, List<TimelineEntry> entries, Job playerJob) {
		this.manager = manager;
		this.rawEntries = entries;
		this.entries = entries.stream().filter(TimelineEntry::enabled).filter(te -> playerJob == null || te.enabledForJob(playerJob)).collect(Collectors.toList());
		secondsFuture = manager.getSecondsFuture();
		secondsPast = manager.getSecondsPast();
		debugMode = manager.getDebugMode();
		showPrePull = manager.getPrePullSetting();
		refresher = new RefreshLoop<>("TimelineRefresher", this, TimelineProcessor::handleTriggers, i -> 200L);
		refresher.start();
	}

	public static TimelineProcessor of(TimelineManager manager, InputStream file, List<? extends TimelineEntry> extra, Job playerJob, LanguageReplacements replacements) {
		List<TextFileTimelineEntry> timelineEntries;
		try {
			timelineEntries = TimelineParser.parseMultiple(IOUtils.readLines(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		List<TimelineEntry> all = new ArrayList<>(timelineEntries);
		for (int i = 0; i < all.size(); i++) {
			TimelineEntry currentItem = all.get(i);
			final String originalName = currentItem.name();
			final String originalSync = currentItem.sync() == null ? null : currentItem.sync().pattern();
			String newName = originalName;
			String newSync = originalSync;
			if (originalName != null) {
				for (var textReplacement : replacements.replaceText().entrySet()) {
					newName = textReplacement.getKey().matcher(newName).replaceAll(textReplacement.getValue());
				}
			}
			if (originalSync != null) {
				for (var syncReplacement : replacements.replaceSync().entrySet()) {
					newSync = syncReplacement.getKey().matcher(newSync).replaceAll(syncReplacement.getValue());
				}
			}
			if (!Objects.equals(originalName, newName) || !Objects.equals(originalSync, newSync)) {
				Pattern newSyncFinal = newSync == null ? null : Pattern.compile(newSync);
				all.set(i, new TranslatedTextFileEntry(currentItem, newName, newSyncFinal));
			}
		}

		// Remove things that have been overridden
		for (TimelineEntry customEntry : extra) {
			all.removeIf(customEntry::shouldSupersede);
		}
		all.addAll(extra);
		all.sort(Comparator.naturalOrder());
		return new TimelineProcessor(manager, all, playerJob);
	}

	public double getEffectiveTime() {
		if (lastSync == null) {
			return 0;
		}
		long millisSinceEvent = lastSync.line.getEffectiveTimeSince().toMillis();
		return lastSync.lastSyncTime + (millisSinceEvent / 1000.0d);
	}

	public @Nullable TimelineSync getLastSync() {
		return lastSync;
	}

	public void setLastSync(@Nullable TimelineSync lastSync) {
		this.lastSync = lastSync;
	}

	public void processActLine(ACTLogLineEvent event) {
		// Skip spammy syncs
		if (lastSync != null && lastSync.line.getEffectiveTimeSince().toMillis() < 10) {
			return;
		}
		// To save on processing time, ignore some events that will never be found in a timeline
		int num = event.getLineNumber();
		// Things that can be ignored:
		/*
			1. Change Zone
			2. Change Primary Player
			11. Party List
			12. Player Stats
			24. DoT tick
			28. Waymarks
			29. Player marker
			31. Gauge
			37. Action resolved (probably can ignore)
			38. Status effect list
			39. HP Update
			200 and up. Only used by the ACT plugin itself for debug messages and such.
		 */
		if (num == 1 || num == 2 || num == 11 || num == 12 || num == 24 || num == 28 || num == 29 || num == 31 || num == 37 || num == 38 || num == 39 || num > 200) {
			return;
		}
		String emulatedActLogLine = event.getEmulatedActLogLine();
		double timeNow = getEffectiveTime();
		Optional<TimelineEntry> newSync = entries.stream().filter(entry -> entry.shouldSync(timeNow, emulatedActLogLine)).findFirst();
		newSync.ifPresent(rawTimelineEntry -> {
			double timeToSyncTo = rawTimelineEntry.getSyncToTime();
			double effectiveTimeBefore = getEffectiveTime();
			boolean firstSync = lastSync == null;
			lastSync = new TimelineSync(event, timeToSyncTo, rawTimelineEntry);
			log.trace("New Sync: {} -> {} ({})", rawTimelineEntry, timeToSyncTo, emulatedActLogLine);
			double effectiveTimeAfter = getEffectiveTime();

			double delta = effectiveTimeAfter - effectiveTimeBefore;
			log.trace("Timeline jumped by {} ({} -> {})", delta, effectiveTimeBefore, effectiveTimeAfter);
			// Only reprocess timeline triggers if the sync changed our timing by more than a couple seconds (i.e.
			// we want to know whether the sync was actually a jump/phase change/whatever, not just time skew).
			if (firstSync || Math.abs(delta) > 4.0) {
				reprocessTriggers();
			}
		});
	}

	public List<TimelineEntry> getEntries() {
		return Collections.unmodifiableList(entries);
	}

	public List<TimelineEntry> getRawEntries() {
		return Collections.unmodifiableList(rawEntries);
	}

	private double getEffectiveLastSyncTime() {
		if (lastSync == null) {
			return 0.0d;
		}
		else {
			return lastSync.lastSyncTime + lastSync.line.getEffectiveTimeSince().toMillis() / 1000.0;
		}
	}

	public List<VisualTimelineEntry> getCurrentTimelineEntries() {
		if (lastSync == null && !showPrePull.get()) {
			return Collections.emptyList();
		}
		double effectiveLastSyncTime = getEffectiveLastSyncTime();
		boolean debug = debugMode.get();
		int barTimeBasis = manager.getBarTimeBasis().get();
		return entries.stream()
				.filter(entry -> isLastSync(entry) && debug
						// TODO: this doesn't show 'active' timeline entries
						|| (entry.time() + (entry.duration() == null ? 0 : entry.duration()) > (effectiveLastSyncTime - secondsPast.get())
						&& entry.time() < (effectiveLastSyncTime + secondsFuture.get())
						&& (entry.name() != null || debug)))
				.map(entry -> new VisualTimelineEntry(entry, isLastSync(entry), entry.time() - effectiveLastSyncTime, barTimeBasis))
				.collect(Collectors.toList());
	}

	private List<UpcomingCall> upcomingTriggers = Collections.emptyList();

	public class UpcomingCall implements HasDuration, HasOptionalIconURL {

		private final double timelineTime;
		private final double callTime;
		private final Duration effectiveDuration;
		private final boolean isPreCall;
		private final TimelineEntry entry;

		UpcomingCall(TimelineEntry entry) {
			this.entry = entry;
			timelineTime = entry.time();
			callTime = entry.effectiveCalloutTime();
			if (Math.abs(timelineTime - callTime) < 0.1) {
				// Use some kind of sane default, this doesn't really matter
				effectiveDuration = Duration.ofSeconds(10);
				isPreCall = false;
			}
			else {
				effectiveDuration = Duration.ofMillis((long) ((timelineTime - callTime) * 1000));
				isPreCall = true;
			}
		}

		public boolean isPreCall() {
			return isPreCall;
		}

		public double timeUntilCall() {
			return callTime - getEffectiveTime();
		}

		public double timeUntilTimelineEntry() {
			return timelineTime - getEffectiveTime();
		}

		@Override
		public Duration getEstimatedRemainingDuration() {
			return Duration.ofMillis(Math.max(0, (long) (timeUntilTimelineEntry() * 1000)));
		}

		@Override
		public Duration getEstimatedTimeSinceExpiry() {
			return Duration.ofMillis((long) (timeUntilTimelineEntry() * -1000));
		}

		@Override
		public Duration getInitialDuration() {
			return effectiveDuration;
		}

		@Override
		public Duration getEffectiveTimeSince() {
			return Duration.ofMillis((long) (timeUntilCall() * 1000)).minus(getEstimatedRemainingDuration());
		}

		public TimelineEntry getEntry() {
			return entry;
		}

		@Override
		public @Nullable HasIconURL getIconUrl() {
			URL rawIcon = entry.icon();
			if (rawIcon == null) {
				return null;
			}
			else {
				return () -> rawIcon;
			}
		}
	}

	private void reprocessTriggers() {
		if (lastSync == null) {
			upcomingTriggers = Collections.emptyList();
			return;
		}
		List<UpcomingCall> out = new ArrayList<>();

		double effectiveLastSyncTime = getEffectiveLastSyncTime();

		for (TimelineEntry entry : entries) {
			if (!entry.callout()) {
				continue;
			}
			double timeUntilCall = entry.effectiveCalloutTime() - effectiveLastSyncTime;
			if (timeUntilCall > -0.1 || entry == lastSync.original || entry.time() > lastSync.lastSyncTime) {
				out.add(new UpcomingCall(entry));
			}
		}
		upcomingTriggers = out;
		refresher.refreshNow();
	}

	private void handleTriggers() {
		if (upcomingTriggers.isEmpty()) {
			return;
		}
		Iterator<UpcomingCall> iter = upcomingTriggers.iterator();
		while (iter.hasNext()) {
			UpcomingCall next = iter.next();
			if (next.timeUntilCall() <= 0) {
				manager.doTriggerCall(next);
				iter.remove();
			}
		}
	}

	private boolean isLastSync(TimelineEntry entry) {
		return lastSync != null && lastSync.original == entry;
	}

	public void reset() {
		lastSync = null;
	}

}
