package mshaders;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.scene.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;

/**
 * The logo on the main menu lit by its normal map (normals/ui/logo.png, drawn by the maker): a soft light from the
 * upper left, and every few seconds a gleam sweeping over it from left to right, as over polished metal.
 *
 * The game draws the logo in the menu itself (MenuFragment, "logo" from the atlas, worked out from the screen's size
 * each frame); the same place is worked out here, and the logo drawn again over it through the lighting shader - only
 * where the normal map has something (a part not drawn yet keeps the game's logo as it is). An element of the menu's
 * own group, so the dialogs opened over the menu stay over it.
 */
class LogoLight{
    private static Shader shader;
    private static Texture normal;
    private static boolean failed;

    static void init(){
        // the normal map is drawn for the mod's own logo: with the mod's pictures left out the game's logo is shown, and
        // it did not fit ("the logo's normal map is put on even when the mod's textures are not loaded")
        if(!ArtSwitch.on) return;
        try{
            var mod = Vars.mods.getMod(ShaderMod.class);
            Fi file = mod == null || mod.root == null ? null : mod.root.child("normals").child("ui").child("logo.png");
            if(file == null || !file.exists() || Surfaces.template(file)) return;
            normal = new Texture(file);
            normal.setFilter(Texture.TextureFilter.linear);
            shader = new Shader(Cutaway.VERT,
                "varying lowp vec4 v_color;\n" +
                "varying highp vec2 v_texCoords;\n" +
                "uniform highp sampler2D u_texture;\n" +
                "uniform sampler2D u_normal;\n" +
                "uniform vec4 u_region;\n" +   // the logo on its page: u, v, u2, v2
                "uniform float u_sweep;\n" +   // where the gleam is, 0 .. 1 over the logo (beyond: none)
                "void main(){\n" +
                "    vec4 c = texture2D(u_texture, v_texCoords);\n" +
                "    vec2 q = (v_texCoords - u_region.xy) / (u_region.zw - u_region.xy);\n" +
                "    vec4 m = texture2D(u_normal, q);\n" +
                "    if(c.a < 0.01 || m.a < 0.5) discard;\n" +
                "    vec3 n = normalize(m.rgb * 2.0 - 1.0);\n" +
                // a soft light from the upper left, and a little from all round
                "    vec3 l = normalize(vec3(-0.5, 0.6, 0.75));\n" +
                "    float diffuse = max(dot(n, l), 0.0);\n" +
                "    float level = l.z;\n" +
                "    vec3 col = c.rgb * (0.45 + 0.65 * diffuse / max(level, 0.01) * 0.85);\n" +
                // the gleam: a narrow slanted band moving to the right, caught by the faces turned to it
                "    float band = exp(-pow((q.x + q.y * 0.25 - u_sweep) / 0.045, 2.0));\n" +
                "    vec3 h = normalize(vec3(0.6, 0.2, 1.0));\n" +
                "    float spec = pow(max(dot(n, h), 0.0), 12.0);\n" +
                "    col += vec3(1.0, 0.97, 0.9) * band * (0.25 + spec * 0.9);\n" +
                "    gl_FragColor = vec4(col, c.a * v_color.a);\n" +
                "}\n");
            Vars.ui.menuGroup.addChild(new Element(){
                @Override
                public void draw(){
                    drawLogo();
                }
            });
            Log.info("[mshaders] the menu's logo is lit by its normal map");
        }catch(Throwable t){
            failed = true;
            Log.err("[mshaders] the menu's logo stays as it is", t);
        }
    }

    private static void drawLogo(){
        if(failed || shader == null || normal == null || !Vars.state.isMenu()) return;
        TextureRegion logo = Core.atlas.find("logo");
        if(logo == null || !logo.found()) return;
        // where the game draws it (MenuFragment)
        float fx = Core.graphics.getWidth(), fy = Core.graphics.getHeight() - Core.scene.marginTop;
        float logoscl = Scl.scl(1f) * logo.scale;
        float logow = Math.min(logo.width * logoscl, Core.graphics.getWidth() - Scl.scl(20f));
        float logoh = logow * (float)logo.height / logo.width;
        float x = (int)(fx / 2f), y = (int)(fy - 6f - logoh) + logoh / 2f - (Core.graphics.isPortrait() ? Scl.scl(30f) : 0f);
        if(Core.settings.getBool("macnotch")) y -= Scl.scl(Vars.macNotchHeight);
        // the gleam crosses every 7 seconds, taking 1.6 of them
        float period = 7f, cross = 1.6f, t = (Time.globalTime / 60f) % period;
        float sweep = t < cross ? -0.2f + 1.5f * t / cross : 5f;
        try{
            Draw.flush();
            Draw.shader(shader);
            Draw.flush();
            normal.bind(1);
            Gl.activeTexture(Gl.texture0);
            shader.bind();
            shader.setUniformi("u_normal", 1);
            shader.setUniformf("u_region", logo.u, logo.v, logo.u2, logo.v2);
            shader.setUniformf("u_sweep", sweep);
            Draw.color();
            Draw.rect(logo, x, y, logow, logoh);
            Draw.flush();
            Draw.shader();
        }catch(Throwable e){
            failed = true;
            Draw.shader();
            Log.err("[mshaders] the menu's logo cannot be lit", e);
        }
    }
}
