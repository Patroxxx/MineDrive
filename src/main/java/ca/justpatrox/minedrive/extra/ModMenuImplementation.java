package ca.justpatrox.minedrive.extra;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import ca.justpatrox.minedrive.gui.AccountLinkScreen;

public class ModMenuImplementation implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AccountLinkScreen::new;
    }
}
