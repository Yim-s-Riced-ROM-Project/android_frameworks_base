#extension GL_OES_EGL_image_external : require
#extension GL_OES_standard_derivatives : enable

// Tear screen-off glitch: torn horizontal slices with a red/blue split, dropped frames, then
// band-by-band dropout to black. Uniforms come from GlitchSchedule.
precision highp float;

uniform samplerExternalOES texUnit;
uniform vec2 resolution;
uniform float level;
uniform float tick;
uniform float intensity;
uniform float split;
uniform float share;
uniform float dropFrame;
uniform float roll;
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
    float s = split * resolution.x;
    float dx = 0.0;
    for (int k = 0; k < 13; k++) {
        float fk = float(k);
        if (fk >= share) break;
        float y = hash(vec2(tick, fk * 4.0 + 1.0)) * resolution.y;
        float r = hash(vec2(tick, fk * 4.0 + 2.0)) * hash(vec2(tick, fk * 4.0 + 3.0));
        float h = (8.0 + r * 170.0) * u;
        if (gP.y >= y && gP.y < y + h) {
            dx = (hash(vec2(tick, fk * 4.0 + 4.0)) - 0.5) * 2.0 * (40.0 + intensity * 280.0) * u;
            s = split * resolution.x * (1.0 + 2.0 * hash(vec2(tick, fk + 50.0)));
        }
    }
    vec3 c = splitFetch(vec2(gP.x - dx, gP.y + roll * resolution.y), s);
    c *= mix(1.0, 0.12, dropFrame);
    float band = floor(gP.y / (24.0 * u));
    if (hash(vec2(band, 4242.0)) < dark) {
        c = vec3(0.0);
    }
    c *= 1.0 - dark * 0.5;
    gl_FragColor = vec4(c, 1.0);
}
