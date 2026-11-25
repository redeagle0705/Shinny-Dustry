package mshaders;

import arc.*;
import arc.files.*;
import arc.util.*;
import mindustry.*;
import mindustry.mod.Mods.*;

import java.io.*;

/**
 * The mod's own pictures - the sprites drawn for it (sprites-override/) and the normal maps that go with them - can be
 * left out, to take less memory (friends of the maker: "the mod takes memory as it loads"). As soon as any mod brings
 * sprites the game packs every sprite of the game anew at start (Mods.loadAsync), on a larger page; with none it keeps
 * the pages it ships with. Measured: some 130 MB of the mod's memory is that.
 *
 * Takes effect at the next start. Off: before the game packs, this mod's folder is made to show no sprites-override/
 * (its LoadedMod.root is wrapped so that child("sprites-override") finds nothing), the game keeps its own sprites, and
 * no normal maps are read (they are drawn for the mod's sprites and would not fit the game's).
 */
class ArtSwitch{
    /** Whether the mod's own pictures are used in this run (read once, at start). */
    static boolean on = true;

    /** From the mod's constructor: before the game packs the sprites of mods. */
    static void atStart(){
        on = Core.settings.getBool(ShaderMod.artOn, true);
        if(on) return;
        // the mod's LoadedMod is made right after its constructor returns; the packing comes a good while later
        Core.app.post(ArtSwitch::hide);
    }

    private static void hide(){
        try{
            LoadedMod mod = Vars.mods.getMod(ShaderMod.class);
            if(mod == null || mod.root instanceof NoArt) return;
            var field = LoadedMod.class.getField("root");
            field.setAccessible(true);
            field.set(mod, new NoArt(mod.root));
            Log.info("[mshaders] the mod's own sprites left out (setting): the game keeps its own");
        }catch(Throwable t){
            Log.err("[mshaders] could not leave the mod's sprites out", t);
        }
    }

    /** The mod's folder, but with no sprites-override/ in it. */
    static class NoArt extends Fi{
        final Fi inner;

        NoArt(Fi inner){
            super(new File(inner.path()), inner.type());
            this.inner = inner;
        }

        @Override
        public Fi child(String name){
            if(name.equals("sprites-override") || name.equals("sprites")) return inner.child("mshaders-no-sprites");
            return inner.child(name);
        }

        @Override public Fi[] list(){ return inner.list(); }
        @Override public boolean exists(){ return inner.exists(); }
        @Override public boolean isDirectory(){ return inner.isDirectory(); }
        @Override public String name(){ return inner.name(); }
        @Override public String path(){ return inner.path(); }
        @Override public Fi parent(){ return inner.parent(); }
        @Override public InputStream read(){ return inner.read(); }
        @Override public long length(){ return inner.length(); }
        @Override public boolean delete(){ return inner.delete(); }
        @Override public String toString(){ return inner.toString(); }
    }
}
