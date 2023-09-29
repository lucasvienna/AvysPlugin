package eu.darkbot.avyiel.config;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.config.annotations.Visibility;
import eu.darkbot.api.managers.OreAPI;
import java.util.EnumSet;
import java.util.Set;

@Configuration("ore_trader.config")
public class OreTraderConfig {
  @Option("ore_trader.config.enabled")
  public boolean ENABLED = false;

  @Option("ore_trader.config.ores")
  @Dropdown(options = Ores.class, multi = true)
  public Set<OreAPI.Ore> ORES = EnumSet.allOf(OreAPI.Ore.class);

  @Option("ore_trader.config.map")
  @Dropdown(options = Maps.class)
  public String MAP = "X-1";

  @Option("ore_trader.config.finish_target")
  public boolean FINISH_TARGET = false;

  @Option("ore_trader.config.advanced")
  @Visibility(Visibility.Level.ADVANCED)
  public Advanced ADVANCED = new Advanced();

  public static class Advanced {
    @Option("ore_trader.config.advanced.sell_delay")
    @Number(min = 0, max = 12_000, step = 1_000)
    public int SELL_DELAY = 5_000;

    @Option("ore_trader.config.advanced.sell_interval")
    @Number(min = 0, max = 1_000, step = 100)
    public int SELL_INTERVAL = 300;
  }
}
