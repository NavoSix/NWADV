package com.northwoodsadv.ride.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * One shader for terrain, roads, trees, water and the bike: wrapped Lambert sun + sky/ground
 * ambient, sun-tinted exponential haze, and procedural fine detail so large flat colours read
 * as ground. u_mode picks the material:
 *
 * - 0 ground: the colour alpha channel marks forest floor, which fades to canopy green with
 *   distance (beyond the range where individual trees are drawn). Steep faces (road cuts, gully
 *   sides) show bare soil and rock.
 * - 1 objects (trees, logs, the bike); u_light dims them in shade. Colour alpha below 1 marks
 *   self-lit parts such as lamps.
 * - 2 water: animated ripples, Fresnel sky reflection and sun glints, blended over the bed.
 * - 3 effects (wake, spray): vertex alpha.
 */
object WorldShader {
    private const val VERT = """
attribute vec3 a_position;
attribute vec3 a_normal;
attribute vec4 a_color;
uniform mat4 u_projView;
uniform vec3 u_offset;
uniform mat4 u_model;
uniform float u_useModel;
varying vec3 v_pos;
varying vec3 v_normal;
varying vec4 v_color;
void main() {
    vec4 p = vec4(a_position, 1.0);
    vec3 n = a_normal;
    if (u_useModel > 0.5) { p = u_model * p; n = (u_model * vec4(a_normal, 0.0)).xyz; }
    p.xyz += u_offset;
    v_pos = p.xyz;
    v_normal = n;
    v_color = a_color;
    gl_Position = u_projView * p;
}
"""
    private const val COMMON = """
#ifdef GL_ES
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif
uniform vec3 u_sunDir;
uniform vec3 u_camPos;
uniform vec3 u_fogColor;
uniform vec3 u_skyTop;
uniform float u_fogDensity;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
// Haze picks up a warm glow toward the sun.
vec3 hazeColor(vec3 view) {
    float s = max(dot(view, u_sunDir), 0.0);
    return u_fogColor + vec3(0.20, 0.14, 0.05) * pow(s, 8.0);
}
"""
    private const val FRAG = COMMON + """
uniform vec3 u_canopy;
uniform vec2 u_canopyRange;
uniform float u_detail;
uniform float u_mode;
uniform float u_light;
uniform float u_time;
varying vec3 v_pos;
varying vec3 v_normal;
varying vec4 v_color;
float wave(vec2 p) {
    return vnoise(p * 1.3 + vec2(u_time * 0.55, u_time * 0.32)) + 0.5 * vnoise(p * 3.1 - vec2(u_time * 0.8, -u_time * 0.45));
}
void main() {
    vec3 n = normalize(v_normal);
    vec3 toCam = u_camPos - v_pos;
    float dist = length(toCam);
    vec3 view = -toCam / max(dist, 0.001);
    vec3 base = v_color.rgb;
    float alpha = 1.0;
    float near = 1.0 - smoothstep(20.0, 120.0, dist);
    vec3 sunCol = vec3(1.0, 0.95, 0.85);
    vec3 lit;
    if (u_mode > 1.5 && u_mode < 2.5) {
        // Water.
        float e = 0.2;
        vec2 p = v_pos.xz;
        float h0 = wave(p);
        vec3 nw = normalize(vec3(-(wave(p + vec2(e, 0.0)) - h0) / e * 0.18, 1.0, -(wave(p + vec2(0.0, e)) - h0) / e * 0.18));
        vec3 v = -view;
        float fres = 0.04 + 0.96 * pow(1.0 - max(dot(v, nw), 0.0), 5.0);
        vec3 r = reflect(view, nw);
        // Woods crowd the sky out of reflections; mostly it mirrors dark banks and canopy.
        vec3 sky = mix(u_canopy * 1.4, mix(u_fogColor, u_skyTop, clamp(r.y * 1.8, 0.0, 1.0)), 0.55);
        float spec = pow(max(dot(r, u_sunDir), 0.0), 90.0);
        vec3 body = base * (0.55 + 0.45 * max(u_sunDir.y, 0.0));
        lit = mix(body, sky * u_light, clamp(fres * 0.6 + 0.1, 0.0, 1.0)) + sunCol * spec * 1.4 * u_light;
        alpha = clamp(0.62 + fres * 0.5, 0.0, 0.96);
    } else {
        if (u_mode < 0.5) {
            // Bare soil and rock on steep ground (cut banks, gully sides).
            float steep = 1.0 - smoothstep(0.6, 0.85, n.y);
            float rk = smoothstep(0.45, 0.75, vnoise(v_pos.xz * 0.35 + v_pos.y * 0.2));
            vec3 soil = mix(vec3(0.40, 0.31, 0.21), vec3(0.50, 0.49, 0.46), rk);
            float lum = dot(base, vec3(0.3, 0.59, 0.11));
            base = mix(base, soil * clamp(lum / 0.26, 0.45, 1.1), steep * 0.8);
            // Forest floor -> canopy with distance.
            float forest = 1.0 - v_color.a;
            base = mix(base, u_canopy * (0.85 + 0.3 * vnoise(v_pos.xz * 0.05)), forest * smoothstep(u_canopyRange.x, u_canopyRange.y, dist));
        }
        // Fine detail (gravel, grass, bark) fading out with distance.
        float d = vnoise(v_pos.xz * 3.1) * 0.5 + vnoise(v_pos.xz * 0.7) * 0.3 + vnoise(v_pos.xz * 9.0) * 0.2;
        base *= 1.0 + u_detail * (d - 0.5) * 0.4 * near;
        float sun = max(dot(n, u_sunDir) * 0.8 + 0.2, 0.0) * u_light;
        float sky = 0.5 + 0.5 * n.y;
        vec3 amb = mix(vec3(0.24, 0.21, 0.17), vec3(0.40, 0.47, 0.60), sky) * (0.55 + 0.45 * u_light);
        lit = base * (sunCol * sun * 1.0 + amb * 0.62);
        if (u_mode > 2.5) alpha = v_color.a;
        // Objects: colour alpha below 1 marks self-lit parts (lamps), glowing at any sun angle.
        else if (u_mode > 0.5) lit = mix(lit, base * 1.1, 1.0 - v_color.a);
    }
    // Gentle filmic shoulder and a touch more saturation.
    lit = lit * 1.12 / (1.0 + lit * 0.12);
    float g = dot(lit, vec3(0.3, 0.59, 0.11));
    lit = mix(vec3(g), lit, 1.12);
    float fog = 1.0 - exp(-pow(dist * u_fogDensity, 2.0));
    gl_FragColor = vec4(mix(lit, hazeColor(view), fog), alpha);
}
"""

    private const val SKY_VERT = """
attribute vec3 a_position;
uniform mat4 u_invProjView;
varying vec3 v_dir;
void main() {
    vec4 far = u_invProjView * vec4(a_position.xy, 1.0, 1.0);
    vec4 nr = u_invProjView * vec4(a_position.xy, -1.0, 1.0);
    v_dir = far.xyz / far.w - nr.xyz / nr.w;
    gl_Position = vec4(a_position.xy, 0.9999, 1.0);
}
"""
    private const val SKY_FRAG = COMMON + """
uniform float u_time;
varying vec3 v_dir;
void main() {
    vec3 d = normalize(v_dir);
    float up = max(d.y, 0.0);
    vec3 col = mix(hazeColor(d), u_skyTop, pow(smoothstep(0.0, 0.6, up), 0.7));
    // Sun disc and glow.
    float s = max(dot(d, u_sunDir), 0.0);
    col += vec3(1.0, 0.9, 0.7) * (pow(s, 600.0) * 2.0 + pow(s, 40.0) * 0.18);
    // Fair-weather cumulus on a plane overhead.
    if (d.y > 0.02) {
        vec2 p = d.xz / d.y * 0.7 + vec2(u_time * 0.004, 0.0);
        float c = vnoise(p * 1.1) * 0.55 + vnoise(p * 2.7) * 0.3 + vnoise(p * 6.3) * 0.15;
        c = smoothstep(0.55, 0.8, c) * smoothstep(0.02, 0.25, d.y);
        vec3 cloud = mix(vec3(0.78, 0.80, 0.84), vec3(1.0, 0.99, 0.96), smoothstep(0.55, 0.9, c + s * 0.3));
        col = mix(col, cloud, c * 0.85);
    }
    // Below the horizon (only seen past the terrain edge): haze.
    col = mix(col, u_fogColor, smoothstep(0.0, -0.05, d.y));
    gl_FragColor = vec4(col, 1.0);
}
"""

    fun create(): ShaderProgram = compile(VERT, FRAG)

    fun createSky(): ShaderProgram = compile(SKY_VERT, SKY_FRAG)

    private fun compile(v: String, f: String): ShaderProgram {
        ShaderProgram.pedantic = false
        val s = ShaderProgram(v, f)
        if (!s.isCompiled) Gdx.app.error("WorldShader", s.log)
        return s
    }
}
