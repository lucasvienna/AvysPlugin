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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Feature(name = "Ore Trader", description = "Sell ore when cargo is full")
public class OreTrader extends TemporalModule implements Behavior, Configurable<OreTraderConfig> {
  private OreTraderConfig config;

  private final PluginAPI api;
  private final BotAPI bot;
  private final HeroAPI hero;
  private final OreAPI ores;
  private final StatsAPI stats;
  private final EntitiesAPI entities;
  private final PetAPI pet;
  private final StarSystemAPI starSystem;
  private final MovementAPI movement;

  private boolean isSelling = false;
  private long sellClick = 0, waitUntil = Long.MAX_VALUE;
  private Iterator<OreAPI.Ore> sellableOres;
  private GameMap targetMap;
  private Portal exitPortal;
  private Status status = Status.IDLE;

  private enum Status {
    IDLE("Module not enabled"),
    SELLING("Selling ore"),
    NAVIGATING_BASE("Cargo full, navigating to Base Map"),
    NAVIGATING_REFINERY("Cargo full, navigating to Refinery");

    private final String message;

    Status(String message) {
      this.message = message;
    }
  }

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
    super.install(api);

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
  }

  @Override
  public void setConfig(ConfigSetting<OreTraderConfig> config) {
    this.config = config.getValue();
  }

  @Override
  public boolean canRefresh() {
    return !hero.isMoving() && status != Status.SELLING;
  }

  @Override
  public String getStatus() {
    return "Ore Trader | " + status.message;
  }

  @Override
  public void onTickBehavior() {
    if (shouldEnableModule()) enableModule();
  }

  @Override
  public void onTickModule() {
    if (shouldBailOut()) goBack();

    tickModule();

    if (finishedSellingOres()) {
      ores.showTrade(false, null);
      goBack();
    }
  }

  @Override
  public void goBack() {
    cleanUp();
    super.goBack();
  }

  private void tickModule() {
    if (hero.hasPet()) pet.setEnabled(false);
    hero.setRoamMode();

    if (targetMap == null) targetMap = getTargetMap();
    if (targetMap.getId() != hero.getMap().getId()) {
      status = Status.NAVIGATING_BASE;
      bot.setModule(api.requireInstance(MapModule.class)).setTarget(targetMap);
      return;
    }

    Station.Refinery refinery = findRefinery();
    if (refinery != null) navigateAndSell(refinery);
  }

  private void cleanUp() {
    isSelling = false;
    sellClick = 0;
    waitUntil = Long.MAX_VALUE;
    sellableOres = null;
    targetMap = null;
    exitPortal = null;
    status = Status.IDLE;
  }

  private boolean shouldEnableModule() {
    if (!config.ENABLED) return false;

    return !Captcha.isActive(entities.getBoxes()) // no captcha is active
        && !config.ORES.isEmpty() // we have configured ores
        && (!config.FINISH_TARGET || hero.getTarget() == null) // not waiting for target to finish
        && (stats.getCargo() >= stats.getMaxCargo() && stats.getMaxCargo() != 0) // cargo is full
        && (!hero.getMap().isGG() || hasGateExit()) // we can safely exit a GG
        && resumeAfterNavigation(); // we ceded control to the MapModule and have now arrived
  }

  private void enableModule() {
    if (bot.getModule() != this) bot.setModule(this);
  }

  /**
   * Workaround for the fact that we're a TemporalModule and the navigation is also a temporal.
   * Determines whether we've just finished navigating to the sell map and should resume module
   * execution, or we're not navigating base.
   */
  private boolean resumeAfterNavigation() {
    return status != Status.NAVIGATING_BASE || bot.getModule().getClass() != MapModule.class;
  }

  /** Prevent cases where auto-refining happens while flying to the Refinery. */
  private boolean shouldBailOut() {
    return !isSelling && (hasCargoDecreased() || isStuckInGate());
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
  private @Nullable Station.Refinery findRefinery() {
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

  private @NotNull GameMap getTargetMap() {
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
      status = Status.NAVIGATING_REFINERY;
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
        status = Status.SELLING;
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
      isSelling = true;
      ores.sellOre(ore);
    }
  }

  private boolean finishedSellingOres() {
    return status == Status.SELLING // we're actually selling
        && config.ORES.stream().filter(Objects::nonNull).allMatch(this::hasSoldOre);
  }

  private boolean hasSoldOre(OreAPI.Ore ore) {
    int amount = ores.getAmount(ore);
    return ore == OreAPI.Ore.PALLADIUM
        ? !hero.getMap().getName().equals("5-2") || amount < 15
        : amount <= 0;
  }
}
