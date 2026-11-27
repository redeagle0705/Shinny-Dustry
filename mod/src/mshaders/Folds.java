package mshaders;

import arc.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.SettingsMenuDialog.*;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.*;

/**
 * Sections for the settings page: a headline that folds away the settings under it when it is pressed.
 *
 * The page had grown to more than forty settings in one unbroken column. Now they are folded under six headlines
 * (water and ice, ground, shadows, lights, blocks, screen colours) - the switch of each effect along with its sliders;
 * pressed, a headline shows or hides what is under it, and which ones are open is remembered.
 *
 * The game's settings page is a list of rows, each of which adds itself to the table (SettingsTable.Setting.add), and
 * the whole table is built again whenever something is added. So a headline is a row that draws a button, and a folded
 * setting is a row that passes "add" on to the real setting only while its headline is open.
 */
class Folds{
    private final SettingsTable table;
    private Header current;
    /** The switch the sliders under it belong to, for their preview pictures (Preview). */
    private String lastSwitch;

    Folds(SettingsTable table){
        this.table = table;
    }

    /** Starts a section; its title is the text "setting.mshaders-section-<id>.name". */
    void section(String id){
        table.pref(current = new Header(id));
    }

    /** A slider that is shown only while the section is open. */
    void slider(String name, int def, int min, int max, int step, StringProcessor text){
        Core.settings.defaults(name, def);
        table.pref(new Folded(current, new SliderSetting(name, def, min, max, step, text, null), name, lastSwitch));
    }

    /** A slider that is shown only while the section is open AND the setting "unlocked" is true: something to be found. */
    void secret(String unlocked, String name, int def, int min, int max, int step, StringProcessor text){
        Core.settings.defaults(name, def);
        Header header = current;
        table.pref(new Folded(header, new SliderSetting(name, def, min, max, step, text, null)){
            @Override
            public void add(SettingsTable table){
                if(Core.settings.getBool(unlocked, false)) super.add(table);
            }
        });
    }

    /** Builds the page again (something that was hidden is to show now). */
    void rebuild(){
        table.rebuild();
    }

    /** A switch that is shown only while the section is open. */
    void check(String name, boolean def){
        Core.settings.defaults(name, def);
        lastSwitch = name;
        CheckSetting setting = new CheckSetting(name, def, null);
        // a setting that takes effect only at the next start: its name in red, saying so
        if(Preview.needsRestart(name)) setting.title = "[scarlet]" + setting.title + " " + Core.bundle.get("mshaders.restart", "(restart needed)");
        table.pref(new Folded(current, setting, name, null));
    }

    /** A button that is shown only while the section is open; its text is "setting.<name>.name". */
    void button(String name, Runnable pressed){
        table.pref(new Folded(current, new Setting(name){
            {
                // no name: "reset to defaults" then passes this row by
                this.name = null;
            }

            @Override
            public void add(SettingsTable table){
                table.button(title, Icon.pencil, Styles.flatt, 24f, pressed).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / arc.scene.ui.layout.Scl.scl(1f))).fillX().height(46f).left().padTop(4f);
                table.row();
            }
        }));
    }

    static class Header extends Setting{
        /** The setting that remembers whether this section is open. */
        final String key;

        Header(String id){
            super("mshaders-section-" + id);
            key = "mshaders-open-" + id;
            // no name: "reset to defaults" then passes this row by
            name = null;
        }

        boolean open(){
            return Core.settings.getBool(key, false);
        }

        @Override
        public void add(SettingsTable table){
            table.button(title, open() ? Icon.downOpen : Icon.rightOpen, Styles.flatt, 24f, () -> {
                Core.settings.put(key, !open());
                table.rebuild();
            }).minWidth(Math.min(500f, Core.graphics.getWidth() / 1.2f / arc.scene.ui.layout.Scl.scl(1f))).fillX().height(46f).left().padTop(16f);
            table.row();
        }
    }

    static class Folded extends Setting{
        final Header header;
        final Setting inner;
        /** Its own preview pictures, and those of the switch above it if it has none (Preview). */
        final String own, fallback;

        Folded(Header header, Setting inner){
            this(header, inner, null, null);
        }

        Folded(Header header, Setting inner, String own, String fallback){
            super(inner.name);
            this.header = header;
            this.inner = inner;
            this.own = own;
            this.fallback = fallback;
        }

        @Override
        public void add(SettingsTable table){
            if(header != null && !header.open()) return;
            inner.add(table);
            // the row just added: hovered, it shows what the setting does
            if(own != null && table.getCells().size > 0){
                var e = table.getCells().peek().get();
                Preview.attach(e, own, fallback);
            }
        }
    }
}
