package eu.darkbot.avyiel.config;

import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.managers.OreAPI.Ore;
import eu.darkbot.util.StringUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Ores implements Dropdown.Options<Ore> {
  public static final List<Ore> ORES = Arrays.stream(Ore.values()).filter(Ore::isSellable).toList();

  @Override
  public Collection<Ore> options() {
    return ORES;
  }

  @Override
  public @NotNull String getText(@Nullable Ore ore) {
    if (ore != null) return StringUtils.capitalize(ore.getName());
    return Objects.toString(null);
  }
}
