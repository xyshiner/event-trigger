package gg.xp.xivsupport.events.actlines.parsers;

import gg.xp.reevent.events.Event;
import gg.xp.xivsupport.events.actlines.events.AbilityUsedEvent;

import java.time.ZonedDateTime;

@SuppressWarnings("unused")
public class Line21Parser extends AbstractACTLineParser<Line21Parser.Fields> {

	public Line21Parser() {
		super(21, Fields.class);
	}

	enum Fields {
		casterId, casterName, abilityId, abilityName, targetId, targetName, castTime, flags, damage;
	}

	@Override
	protected Event convert(FieldMapper<Fields> fields, int lineNumber, ZonedDateTime time) {
		return new AbilityUsedEvent(
				fields.getAbility(Fields.abilityId, Fields.abilityName),
				fields.getEntity(Fields.casterId, Fields.casterName),
				fields.getEntity(Fields.targetId, Fields.targetName),
				fields.getHex(Fields.flags),
				0
//				fields.getLong(Fields.damage)
		);
	}
}