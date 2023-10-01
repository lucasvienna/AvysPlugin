package eu.darkbot.avyiel.config;

import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Number;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.config.annotations.Visibility;
import eu.darkbot.api.managers.OreAPI;

import java.util.HashSet;
import java.util.Set;

@Configuration("ore_trader.config")
public class OreTraderConfig {

  @Option("ore_trader.config.enabled")
  public boolean ENABLED = true;

  @Option("ore_trader.config.ores")
  @Dropdown(options = Ores.class, multi = true)
  public Set<OreAPI.Ore> ORES = new HashSet<>(Ores.ORES);

  @Option("ore_trader.config.finish_target")
  public boolean FINISH_TARGET = true;

  @Option("ore_trader.config.advanced")
  @Visibility(Visibility.Level.ADVANCED)
  public Advanced ADVANCED = new Advanced();

  public static class Advanced {

    @Option("ore_trader.config.advanced.sell_delay")
    @Number(min = 0, max = 5000, step = 500)
    public long SELL_DELAY = 2000;

    @Option("ore_trader.config.advanced.sell_interval")
    @Number(min = 0, max = 1000, step = 100)
    public long SELL_INTERVAL = 300;
  }
}
