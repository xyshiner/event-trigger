package gg.xp.xivsupport.events.triggers.jobs.gui;

import gg.xp.xivsupport.gui.tables.renderers.ComponentListStretchyRenderer;
import gg.xp.xivsupport.gui.tables.renderers.ResourceBarRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Supplier;

public class CdBarRenderer extends ResourceBarRenderer<VisualCdInfo> {


	private final ComponentListStretchyRenderer componentListStretchyRenderer = new ComponentListStretchyRenderer(0);
	private final CdColorProvider colors;

	public CdBarRenderer(CdColorProvider colors) {
		super(VisualCdInfo.class);
		this.colors = colors;
	}

	@Override
	public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
		if (value instanceof VisualCdInfo vci) {
			if (vci.useChargeDisplay()) {
				List<Supplier<Component>> components = vci.makeChargeInfo()
						.stream()
						.map(subVci -> (Supplier<Component>) () -> super.getTableCellRendererComponent(table, subVci, isSelected, hasFocus, row, column))
						.toList();
				componentListStretchyRenderer.setComponents(components);
				return componentListStretchyRenderer;
			}
		}
		return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
	}

	@Override
	protected void formatLabel(@NotNull VisualCdInfo item) {
		bar.setTextColor(colors.getFontColor());
		bar.setTextOptions(item.getLabel());
	}

	@Override
	protected Color getBarColor(double percent, @NotNull VisualCdInfo item) {
		return switch (item.getStatus()) {
			case READY, NOT_YET_USED -> colors.getReadyColor();
			case BUFF_ACTIVE -> colors.getActiveColor();
			case BUFF_PREAPP -> colors.getPreappColor();
			case ON_COOLDOWN -> colors.getOnCdColor();
		};
	}
}
