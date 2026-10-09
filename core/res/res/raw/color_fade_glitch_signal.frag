#extension GL_OES_EGL_image_external : require
#extension GL_OES_standard_derivatives : enable

// Signal loss screen-off glitch: horizontal sync wobble, vertical roll, static, then the
// picture fades out under the noise. Uniforms come from GlitchSchedule.
precision highp float;

uniform samplerExternalOES texUnit;
uniform vec2 resolution;
uniform float level;
uniform float tick;
uniform float intensity;
uniform float split;
uniform float share;
uniform float roll;
uniform float staticAmount;
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
    float band = floor(gP.y / (12.0 * u));
    float sh = sin(band * 0.216 + tick * 1.7) * intensity * 46.0 * u;
    if (hash(vec2(band, tick)) < share) {
        sh += (hash(vec2(band, tick + 0.5)) - 0.5) * intensity * 320.0 * u;
    }
    float y = mod(gP.y + roll * resolution.y, resolution.y);
    vec3 c = splitFetch(vec2(gP.x - sh, y), split * resolution.x);
    float n = hash(floor(gP / (4.0 * u)) + vec2(tick * 7.0, tick * 13.0));
    c = mix(c, 1.0 - (1.0 - c) * (1.0 - n), staticAmount);
    c *= 1.0 - dark;
    gl_FragColor = vec4(c, 1.0);
}
