package com.northwoodsadv.ride.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.glutils.ShaderProgram

/**
 * One shader for terrain, roads, trees and the bike: wrapped Lambert sun + sky/ground ambient,
 * exponential haze, and procedural fine detail so large flat colours read as ground. The colour
 * alpha channel marks forest floor, which fades to canopy green with distance (beyond the range
 * where individual trees are drawn).
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
    private const val FRAG = """
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
uniform float u_fogDensity;
uniform vec3 u_canopy;
uniform vec2 u_canopyRange;
uniform float u_detail;
varying vec3 v_pos;
varying vec3 v_normal;
varying vec4 v_color;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float vnoise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
void main() {
    vec3 n = normalize(v_normal);
    float dist = length(v_pos - u_camPos);
    vec3 base = v_color.rgb;
    // Forest floor -> canopy with distance.
    float forest = 1.0 - v_color.a;
    base = mix(base, u_canopy * (0.85 + 0.3 * vnoise(v_pos.xz * 0.05)), forest * smoothstep(u_canopyRange.x, u_canopyRange.y, dist));
    // Fine detail (gravel, grass, bark) fading out with distance.
    float d = vnoise(v_pos.xz * 3.1) * 0.6 + vnoise(v_pos.xz * 0.7) * 0.4;
    base *= 1.0 + u_detail * (d - 0.5) * 0.35 * (1.0 - smoothstep(20.0, 120.0, dist));
    float sun = max(dot(n, u_sunDir) * 0.8 + 0.2, 0.0);
    float sky = 0.5 + 0.5 * n.y;
    vec3 lit = base * (vec3(1.0, 0.96, 0.88) * sun * 0.95 + mix(vec3(0.25, 0.22, 0.18), vec3(0.42, 0.48, 0.58), sky) * 0.6);
    float fog = 1.0 - exp(-pow(dist * u_fogDensity, 2.0));
    gl_FragColor = vec4(mix(lit, u_fogColor, fog), 1.0);
}
"""

    fun create(): ShaderProgram {
        ShaderProgram.pedantic = false
        val s = ShaderProgram(VERT, FRAG)
        if (!s.isCompiled) Gdx.app.error("WorldShader", s.log)
        return s
    }
}
