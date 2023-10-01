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
import eu.darkbot.avyiel.utils.Pathfinder;
import eu.darkbot.avyiel.utils.VerifierChecker;
import eu.darkbot.shared.modules.TemporalModule;
import eu.darkbot.shared.utils.MapTraveler;
import lombok.val;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;

@Feature(name = "Ore Trader", description = "Sell ore when cargo is full")
public class OreTrader extends TemporalModule implements Behavior, Configurable<OreTraderConfig> {

  private OreTraderConfig config;

  private final BotAPI bot;
  private final HeroAPI hero;
  private final OreAPI ores;
  private final StatsAPI stats;
  private final EntitiesAPI entities;
  private final PetAPI pet;
  private final MovementAPI movement;
  private final MapTraveler traveler;
  private final Pathfinder pathfinder;

  private boolean triedSelling = false, clickedSellButton = false;
  private long sellClick = 0, waitUntil = Long.MAX_VALUE, sellTimeout = Long.MAX_VALUE;
  private Iterator<OreAPI.Ore> sellableOres;
  private GameMap targetMap;
  private Portal exitPortal;
  private Status status = Status.IDLE;

  private enum Status {
    IDLE("Module not enabled"),
    SELLING("Selling ore"),
    NAVIGATING_BASE("Navigating to Base Map"),
    NAVIGATING_REFINERY("Navigating to Refinery");

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
      MovementAPI movement,
      MapTraveler traveler) {
    super(bot);
    super.install(api);

    // Ensure that the verifier is from this plugin and properly signed by yourself. If it isn't,
    // fail with a security exception.
    if (!Arrays.equals(VerifierChecker.class.getSigners(), getClass().getSigners()))
      throw new SecurityException();
    if (!auth.requireDonor()) throw new SecurityException();

    this.bot = bot;
    this.hero = hero;
    this.ores = ores;
    this.entities = entities;
    this.stats = stats;
    this.pet = pet;
    this.movement = movement;
    this.traveler = traveler;

    this.pathfinder = new Pathfinder(hero, api.requireAPI(StarSystemAPI.class), entities);
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
    String message = "Ore Trader | " + status.message;
    if (status == Status.NAVIGATING_BASE) message += " | Sell Map: " + targetMap.getName();
    return message;
  }

  @Override
  public void onTickBehavior() {
    if (shouldEnableModule()) enableModule();
  }

  @Override
  public void onTickModule() {
    if (shouldBailOut()) goBack();

    tickModule();

    if (finishedSellingOres() || sellButtonsBugged()) {
      ores.showTrade(false, null);
      goBack();
    }
  }

  @Override
  public void goBack() {
    cleanUp();
    super.goBack();
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

  private void tickModule() {
    if (hero.hasPet()) pet.setEnabled(false);
    hero.setRoamMode();

    if (targetMap == null) targetMap = pathfinder.getRefineryMap();
    if (!navigateToTargetMap()) return;

    val refinery = pathfinder.findRefinery();
    refinery.ifPresent(this::navigateAndSell);
  }

  /** Navigates to the target map. If already there, returns true. Otherwise, false. */
  private boolean navigateToTargetMap() {
    if (targetMap.getId() == hero.getMap().getId()) return true;

    if (traveler.target != targetMap) traveler.setTarget(targetMap);
    status = Status.NAVIGATING_BASE;
    traveler.tick();

    return false;
  }

  /** While in the target map, navigates to the given refinery and starts selling. */
  private void navigateAndSell(Station.Refinery refinery) {
    if (tradeWindowUnavailable(refinery)) {
      status = Status.NAVIGATING_REFINERY;
      sellClick = 0;

      double angle = refinery.angleTo(hero) + Math.random() * 0.2 - 0.1;
      movement.moveTo(Location.of(refinery, angle, 50 + (100 * Math.random())));
    } else {
      // first opening of the window, mark time opened
      if (sellClick == 0) sellClick = System.currentTimeMillis();
      if (!clickedSellButton && !hero.isMoving() && ores.showTrade(true, refinery)) {
        status = Status.SELLING;
        sellClick = Long.MAX_VALUE;
        sellTimeout =
            System.currentTimeMillis()
                + config.ADVANCED.SELL_INTERVAL * config.ORES.size() // time to sell all ores
                + 2L * config.ADVANCED.SELL_DELAY; // start delay + some buffer
        waitUntil = System.currentTimeMillis() + config.ADVANCED.SELL_DELAY;
        clickedSellButton = true;
      }

      sellNextOre();
    }
  }

  /** Prevent cases where auto-refining happens while flying to the Refinery. */
  private boolean shouldBailOut() {
    return !triedSelling && (hasCargoDecreased() || stuckInGate());
  }

  /** Indicates that the current cargo is far from the max cargo. */
  private boolean hasCargoDecreased() {
    return stats.getCargo() < stats.getMaxCargo() - 100;
  }

  /** Hero is not in the LoW gate and an exit {@link Portal} cannot be found. */
  private boolean stuckInGate() {
    return hero.getMap().isGG() && !hero.getMap().getName().equals("LoW") && exitPortal == null;
  }

  /** Weird condition where we'll be at the refinery but cannot sell. */
  private boolean sellButtonsBugged() {
    return stats.getCargo() >= stats.getMaxCargo()
        && ores.canSellOres()
        && System.currentTimeMillis() > sellTimeout;
  }

  /** Finds a Portal that does not lead to a GG. */
  private boolean hasGateExit() {
    exitPortal =
        entities.getPortals().stream()
            .filter(Objects::nonNull)
            .filter(
                p ->
                    !(p.getTargetMap()
                        .map(gm -> !gm.isGG())
                        .orElse(p.getPortalType().getId() == 1)))
            .min(Comparator.comparingDouble(p -> p.getLocationInfo().distanceTo(hero)))
            .orElse(null);
    return exitPortal != null;
  }

  private void sellNextOre() {
    if (!ores.canSellOres()) return;

    // advance interval timer
    if (System.currentTimeMillis() < waitUntil) return;
    waitUntil = System.currentTimeMillis() + config.ADVANCED.SELL_INTERVAL;

    // if somehow we skipped an ore, or it failed to sell in the first iteration, restart the queue
    if (sellableOres == null || !sellableOres.hasNext()) sellableOres = config.ORES.iterator();
    if (!sellableOres.hasNext()) return;

    OreAPI.Ore ore = sellableOres.next();
    if (ore == null) return;

    if (ore.isSellable() && !hasSoldOre(ore)) {
      triedSelling = true;
      ores.sellOre(ore);
    }
  }

  private boolean finishedSellingOres() {
    return triedSelling && config.ORES.stream().filter(Objects::nonNull).allMatch(this::hasSoldOre);
  }

  private boolean hasSoldOre(OreAPI.Ore ore) {
    int amount = ores.getAmount(ore);
    return ore == OreAPI.Ore.PALLADIUM
        ? !hero.getMap().getName().equals("5-2") || amount < 15
        : amount <= 0;
  }

  /**
   * Check whether the Ore Trade GUI is unavailable. As long as the Hero cannot sell and is either
   * too far away or too much time has passed, the GUI is considered unavailable.
   */
  private boolean tradeWindowUnavailable(Station.Refinery refinery) {
    boolean tooFarAway = movement.getDestination().distanceTo(refinery) > 150D;
    // sellClick will be Long.MAX_VALUE after we try to open the trade window
    boolean timedOut = sellClick != 0 && System.currentTimeMillis() - sellClick > 5000;

    return !ores.canSellOres() && (tooFarAway || timedOut);
  }

  /** Reset state after module completes. */
  private void cleanUp() {
    triedSelling = false;
    clickedSellButton = false;
    sellClick = 0;
    waitUntil = Long.MAX_VALUE;
    sellTimeout = Long.MAX_VALUE;
    sellableOres = null;
    targetMap = null;
    exitPortal = null;
    status = Status.IDLE;
  }
}
