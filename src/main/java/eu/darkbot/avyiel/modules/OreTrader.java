package eu.darkbot.avyiel.modules;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.game.entities.Portal;
import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.AuthAPI;
import eu.darkbot.api.managers.BotAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.OreAPI;
import eu.darkbot.api.managers.PetAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.managers.StatsAPI;
import eu.darkbot.avyiel.config.OreTraderConfig;
import eu.darkbot.avyiel.utils.Captcha;
import eu.darkbot.avyiel.utils.VerifierChecker;
import eu.darkbot.shared.modules.MapModule;
import eu.darkbot.shared.modules.TemporalModule;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

@Feature(name = "ore_trader.feature.name", description = "ore_trader.feature.description")
public class OreTrader extends TemporalModule implements Behavior, Configurable<OreTraderConfig> {
  private OreTraderConfig config;
  private final GameMap targetMap;

  private final PluginAPI api;
  private final BotAPI bot;
  private final HeroAPI hero;
  private final OreAPI ores;
  private final StatsAPI stats;
  private final EntitiesAPI entities;
  private final PetAPI pet;
  private final StarSystemAPI starSystem;
  private final MovementAPI movement;

  private boolean dirty = false;
  private long sellClick = 0, waitUntil = Long.MAX_VALUE;
  private Iterator<OreAPI.Ore> sellableOres;
  private Portal exitPortal;

  public OreTrader(
      PluginAPI api,
      BotAPI bot,
      HeroAPI hero,
      AuthAPI auth,
      OreAPI ores,
      EntitiesAPI entities,
      StatsAPI stats,
      PetAPI pet,
      StarSystemAPI starSystem,
      MovementAPI movement) {
    super(bot);

    // Ensure that the verifier is from this plugin and properly signed by yourself
    // If it isn't, fail with a security exception.
    if (!Arrays.equals(VerifierChecker.class.getSigners(), getClass().getSigners())) {
      throw new SecurityException();
    }
    VerifierChecker.checkAuthenticity(auth);
    // if (!auth.requireDonor()) return;

    this.api = api;
    this.bot = bot;
    this.hero = hero;
    this.ores = ores;
    this.entities = entities;
    this.stats = stats;
    this.pet = pet;
    this.starSystem = starSystem;
    this.movement = movement;

    this.targetMap = getTargetMap();
  }

  @Override
  public void setConfig(ConfigSetting<OreTraderConfig> config) {
    this.config = config.getValue();
  }

  @Override
  public boolean canRefresh() {
    return false;
  }

  @Override
  public String getStatus() {
    return "Ore Trader | Doing something?";
  }

  @Override
  public void onTickBehavior() {
    if (shouldEnableModule()) enableModule();
  }

  @Override
  public void onTickModule() {
    if (shouldBailOut()) goBack();

    tickModule();

    if (finishedSellingOres() && ores.showTrade(false, null)) goBack();
  }

  @Override
  public void goBack() {
    cleanUp();
    super.goBack();
  }

  private void tickModule() {
    if (hero.hasPet()) pet.setEnabled(false);
    hero.setRoamMode();

    // TODO: do I have to exit a GG first?
    //    if (isGG) movement.moveTo(exitPortal);

    if (targetMap.getId() != hero.getMap().getId()) {
      bot.setModule(api.requireInstance(MapModule.class)).setTarget(targetMap);
      return;
    }

    Station.Refinery refinery = findRefinery();
    if (refinery != null) navigateAndSell(refinery);
  }

  private void cleanUp() {
    sellClick = 0;
    waitUntil = Long.MAX_VALUE;
    dirty = false;
    exitPortal = null;
    sellableOres = null;
  }

  private boolean shouldEnableModule() {
    if (!config.ENABLED) return false;

    return !Captcha.isActive(entities.getBoxes()) // no captcha is active
        && !config.ORES.isEmpty() // we have configured ores
        && (!config.FINISH_TARGET || hero.getTarget() == null) // not waiting for target to finish
        && (stats.getCargo() >= stats.getMaxCargo() && stats.getMaxCargo() != 0) // cargo is full
        && (!hero.getMap().isGG() || hasGateExit()); // we can safely exit a GG
  }

  private void enableModule() {
    if (bot.getModule() != this) bot.setModule(this);
  }

  /** Prevent cases where auto-refining happens while flying to the Refinery. */
  private boolean shouldBailOut() {
    return !dirty && (hasCargoDecreased() || isStuckInGate());
  }

  /** Indicates that the current cargo is far from the max cargo. */
  private boolean hasCargoDecreased() {
    return stats.getCargo() < stats.getMaxCargo() - 100;
  }

  /**
   * Indicates that the {@link HeroAPI Hero} is not in the LoW gate and an exit {@link Portal}
   * cannot be found.
   */
  private boolean isStuckInGate() {
    return hero.getMap().isGG() && !hero.getMap().getName().equals("LoW") && exitPortal == null;
  }

  /** Search for a refinery in the current map. */
  private Station.Refinery findRefinery() {
    Collection<? extends Station> bases = this.entities.getStations();
    return bases.stream()
        .filter(b -> b instanceof Station.Refinery && b.getLocationInfo().isInitialized())
        .map(Station.Refinery.class::cast)
        .findFirst()
        .orElse(null);
  }

  /** Finds the closes base map with a refinery. */
  private Optional<GameMap> findTargetMap() {
    GameMap currentMap = hero.getMap();

    int factionId = hero.getEntityInfo().getFaction().ordinal();
    if (factionId == 0 || factionId == 4) return Optional.empty();
    if (currentMap.isGG()) {
      return starSystem.findMap(factionId + "-1");
    }

    return Optional.empty();
  }

  private GameMap getTargetMap() {
    int faction = hero.getEntityInfo().getFaction().ordinal();
    String map = config.MAP.replace('X', Character.forDigit(faction, 10));
    return starSystem.findMap(map).orElseThrow();
  }

  private boolean hasGateExit() {
    exitPortal =
        entities.getPortals().stream()
            .filter(Objects::nonNull)
            .filter(p -> !(p.getTargetMap().orElseThrow().isGG()))
            .min(Comparator.comparingDouble(p -> p.getLocationInfo().distanceTo(hero)))
            .orElse(null);
    return exitPortal != null;
  }

  private void navigateAndSell(Station.Refinery refinery) {
    if (movement.getDestination().distanceTo(refinery) > 200D) {
      double angle = refinery.angleTo(hero) + Math.random() * 0.2 - 0.1;
      movement.moveTo(Location.of(refinery, angle, 100 + (100 * Math.random())));
      sellClick = 0;
    } else {
      if (sellClick == 0) {
        // first opening of the window, mark time opened and time to start selling
        sellClick = System.currentTimeMillis();
        waitUntil = sellClick + config.ADVANCED.SELL_DELAY;
      }

      if (!hero.isMoving()
          && ores.showTrade(true, refinery)
          && System.currentTimeMillis() > waitUntil) {
        // advance clock until next sale
        waitUntil = System.currentTimeMillis() + config.ADVANCED.SELL_INTERVAL;
        sellOres();
      }
    }
  }

  private void sellOres() {
    if (!ores.canSellOres()) return;

    if (sellableOres == null || !sellableOres.hasNext()) sellableOres = config.ORES.iterator();
    if (!sellableOres.hasNext()) return;

    OreAPI.Ore ore = sellableOres.next();
    if (ore == null) return;

    if (ore.isSellable() && !hasSoldOre(ore)) {
      ores.sellOre(ore);
      dirty = true;
    }
  }

  private boolean finishedSellingOres() {
    return config.ORES.stream().filter(Objects::nonNull).allMatch(this::hasSoldOre);
  }

  private boolean hasSoldOre(OreAPI.Ore ore) {
    int amount = ores.getAmount(ore);
    return ore == OreAPI.Ore.PALLADIUM
        ? !hero.getMap().getName().equals("5-2") || amount < 15
        : amount <= 0;
  }
}
