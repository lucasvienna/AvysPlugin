package eu.darkbot.avyiel.utils;

import eu.darkbot.api.game.entities.Station;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.util.ArrayUtils;
import lombok.AllArgsConstructor;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@AllArgsConstructor
public class Pathfinder {

  protected static final List<String> LOWERS = ArrayUtils.asImmutableList("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4", "3-1", "3-2", "3-3", "3-4");
  protected static final List<String> UPPERS = ArrayUtils.asImmutableList("1-5", "1-6", "1-7", "1-8", "2-5", "2-6", "2-7", "2-8", "3-5", "3-6", "3-7", "3-8");
  protected static final List<String> PVP = ArrayUtils.asImmutableList("4-1", "4-2", "4-3");
  protected static final List<String> PIRATE = ArrayUtils.asImmutableList("4-4", "4-5", "5-1", "5-2", "5-3", "5-4");
  protected static final List<String> BLACKLIGHT = StarSystemAPI.BLACK_LIGHT_MAPS;
  protected static final List<String> BASE_MAPS = StarSystemAPI.BASE_MAPS;

  private final HeroAPI hero;
  private final StarSystemAPI starSystem;
  private final EntitiesAPI entities;

  protected enum BaseMap {
    MMO(1),
    EIC(5),
    VRU(9),
    PIRATE(92);

    private final int mapId;

    BaseMap(int mapId) {
      this.mapId = mapId;
    }
  }

  /** Finds the closes base map with a refinery. */
  public Optional<GameMap> findRefineryMap() {
    var factionId = hero.getEntityInfo().getFaction().ordinal();
    if (factionId == 0 || factionId == 4) return Optional.empty(); // should never happen

    var currentMap = hero.getMap();
    var currentMapName = currentMap.getName();
    String HOME = factionId + "-1";
    String OUTPOST = factionId + "-8";
    String NEUTRAL = "5-2";

    // while in LoW, stay there
    if (currentMapName.equals("LoW")) return Optional.of(currentMap);
    // while in GGs, use home map
    if (currentMap.isGG()) return starSystem.findMap(HOME);

    // when already in our home or outpost map, stay there
    if (BASE_MAPS.contains(currentMapName) && isFactionMap()) return Optional.of(currentMap);
    // check battle maps (4-X) or lowers and go to X-1
    if (LOWERS.contains(currentMapName) || PVP.contains(currentMapName))
      return starSystem.findMap(HOME);
    // check uppers and blacklight and go to X-8
    if (UPPERS.contains(currentMapName) || BLACKLIGHT.contains(currentMapName))
      return starSystem.findMap(OUTPOST);
    // check 4-4, 4-5, and 5-X and go to 5-2
    if (PIRATE.contains(currentMapName)) return starSystem.findMap(NEUTRAL);

    // default to the home map
    return starSystem.findMap(HOME);
  }

  /** Tries to find the best map. If not possible, defaults to the faction's home. */
  public GameMap getRefineryMap() {
    var faction = hero.getEntityInfo().getFaction();
    BaseMap map = BaseMap.PIRATE;
    switch (faction) {
      case MMO:
        map = BaseMap.MMO;
        break;
      case EIC:
        map = BaseMap.EIC;
        break;
      case VRU:
        map = BaseMap.VRU;
        break;
    }
    return findRefineryMap().orElse(starSystem.getOrCreateMap(map.mapId));
  }

  /** Finds the closes {@link eu.darkbot.api.game.entities.Station.Refinery} in the current map. */
  public Optional<Station.Refinery> findRefinery() {
    var bases = this.entities.getStations();
    return bases.stream()
        .filter(Objects::nonNull)
        .filter(b -> b instanceof Station.Refinery && b.getLocationInfo().isInitialized())
        .map(Station.Refinery.class::cast)
        .min(Comparator.comparingDouble(p -> p.getLocationInfo().distanceTo(hero)));
  }

  /**
   * Determines whether the current map belongs to the {@link eu.darkbot.api.managers.HeroAPI
   * Hero}'s {@link eu.darkbot.api.game.other.EntityInfo.Faction Faction}.
   */
  private boolean isFactionMap() {
    var factionId = hero.getEntityInfo().getFaction().ordinal();
    if (factionId == 0 || factionId == 4) return false;
    return hero.getMap().getName().startsWith(factionId + "-");
  }
}
