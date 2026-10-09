#extension GL_OES_EGL_image_external : require
#extension GL_OES_standard_derivatives : enable

// Corrupt screen-off glitch: macroblocks copy from wrong places, lose colour channels, or
// smear, then die to black one by one. Uniforms come from GlitchSchedule.
precision highp float;

uniform samplerExternalOES texUnit;
uniform vec2 resolution;
uniform float level;
uniform float tick;
uniform float intensity;
uniform float split;
uniform float share;
uniform float dark;
varying vec2 UV;

vec2 gP;
vec2 gJx;
vec2 gJy;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec4 fetch(vec2 q) {
    q.x = mod(q.x, resolution.x);
    vec2 d = q - gP;
    return texture2D(texUnit, UV + gJx * d.x + gJy * d.y);
}

vec3 splitFetch(vec2 q, float s) {
    return vec3(fetch(q - vec2(s, 0.0)).r, fetch(q).g, fetch(q + vec2(s, 0.0)).b);
}

void main() {
    gP = gl_FragCoord.xy;
    gJx = dFdx(UV);
    gJy = dFdy(UV);
    if (level >= 1.0) {
        gl_FragColor = vec4(texture2D(texUnit, UV).rgb, 1.0);
        return;
    }
    if (level <= 0.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float u = resolution.x / 720.0;
    float b = 48.0 * u;
    vec2 cell = floor(gP / b);
    vec2 grid = max(floor(resolution / b), vec2(1.0));
    vec3 c = splitFetch(gP, split * resolution.x);
    if (hash(cell + vec2(tick * 31.0, tick * 17.0)) < share) {
        vec2 local = gP - cell * b;
        float hx = hash(cell + vec2(tick, 101.0));
        float hy = hash(cell + vec2(tick, 202.0));
        vec2 src = cell + vec2(floor((hx - 0.5) * 8.0 + 0.5), floor((hy - 0.5) * 3.0 + 0.5));
        src = clamp(src, vec2(0.0), grid - 1.0);
        float mode = hash(cell + vec2(tick, 303.0));
        if (mode < 0.45) {
            float ch = floor(hash(cell + vec2(tick, 404.0)) * 3.0);
            vec3 mask = ch < 0.5 ? vec3(1.0, 0.0, 0.0)
                    : (ch < 1.5 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0));
            c = fetch(src * b + local).rgb * mask;
        } else if (mode < 0.8) {
            c = fetch(src * b + local).rgb;
        } else {
            c = fetch(src * b + vec2(local.x, u)).rgb;
        }
    }
    if (hash(cell + vec2(9090.0, 9090.0)) < dark) {
        c = vec3(0.0);
    }
    gl_FragColor = vec4(c, 1.0);
}
