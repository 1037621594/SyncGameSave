package com.example.syncgamesave.pokemon;

import java.io.IOException;

/** A floating tool (Pokemon recognition or screenshot translation) hosted by a service. */
public interface OverlayTool {
    /** Intent extra that selects which tool the launcher and hosting services start. */
    String EXTRA_MODE = "tool_mode";
    String MODE_POKEMON = "pokemon";
    String MODE_TRANSLATE = "translate";

    void start() throws IOException;

    /** Safe to call on a tool whose start() failed part way. */
    void stop();

    void onConfigurationChanged();
}
