package eu.darkbot.avyiel.config;

import eu.darkbot.api.config.annotations.Dropdown;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class Maps implements Dropdown.Options<String> {
  private static final List<String> MAPS = Arrays.asList("X-1", "X-8", "5-2");

  @Override
  public Collection<String> options() {
    return MAPS;
  }
}
