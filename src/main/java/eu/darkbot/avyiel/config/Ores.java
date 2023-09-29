package eu.darkbot.avyiel.config;

import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.managers.OreAPI.Ore;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Ores implements Dropdown.Options<Ore> {
  private static final List<Ore> ores = Arrays.asList(Ore.values());

  @Override
  public Collection<Ore> options() {
    return ores;
  }

  @Override
  public @NotNull String getText(@Nullable Ore ore) {
    if (ore != null) return ore.getName();
    return Dropdown.Options.super.getText(null);
  }
}
