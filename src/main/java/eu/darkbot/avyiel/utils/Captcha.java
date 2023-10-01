package eu.darkbot.avyiel.utils;

import eu.darkbot.api.game.entities.Box;

import java.util.Collection;
import java.util.List;

public class Captcha {

  private static final List<String> CAPTCHA_BOXES = List.of("POISON_PUSAT_BOX_BLACK", "BONUS_BOX_RED");

  /**
   * Checks for an active captcha challenge.
   *
   * @param boxes List of active star system boxes
   * @return Whether a captcha is active
   */
  public static boolean isActive(Collection<? extends Box> boxes) {
    return boxes.stream().anyMatch(box -> CAPTCHA_BOXES.contains(box.getTypeName()));
  }
}
