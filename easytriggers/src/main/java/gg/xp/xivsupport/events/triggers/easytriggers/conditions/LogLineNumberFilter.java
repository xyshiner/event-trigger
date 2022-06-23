package gg.xp.xivsupport.events.triggers.easytriggers.conditions;

import gg.xp.xivsupport.events.ACTLogLineEvent;
import gg.xp.xivsupport.events.actlines.events.HasStatusEffect;
import gg.xp.xivsupport.events.triggers.easytriggers.model.Condition;
import gg.xp.xivsupport.events.triggers.easytriggers.model.NumericOperator;


public class LogLineNumberFilter implements Condition<ACTLogLineEvent> {

	public NumericOperator operator = NumericOperator.EQ;
	@Description("Log Line Number (0-255)")
	public int expected;

	@Override
	public boolean test(ACTLogLineEvent event) {
		return operator.checkLong(Integer.parseInt(event.getRawFields()[0]), expected);
	}


	@Override
	public String fixedLabel() {
		return "ACT Log Line Number";
	}

	@Override
	public String dynamicLabel() {
		return "Log Line Number " + operator.getFriendlyName() + ' ' + expected;
	}
}
