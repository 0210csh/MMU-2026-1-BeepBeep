/*
Copyright 2018 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS-IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

#ifndef RESONANCE_AUDIO_API_RESONANCE_AUDIO_API_H_
#define RESONANCE_AUDIO_API_RESONANCE_AUDIO_API_H_

// EXPORT_API can be used to define the dllimport storage-class attribute.
#if !defined(EXPORT_API)
#define EXPORT_API
#endif

#include <cstddef>  // size_t declaration.
#include <cstdint>  // int16_t declaration.

typedef int16_t int16;

namespace vraudio {

// Rendering modes define CPU load / rendering quality balances.
    enum RenderingMode {
        // Stereo panning, i.e., this disables HRTF-based rendering.
        kStereoPanning = 0,
        // HRTF-based rendering using First Order Ambisonics.
        kBinauralLowQuality,
        // HRTF-based rendering using Second Order Ambisonics.
        kBinauralMediumQuality,
        // HRTF-based rendering using Third Order Ambisonics.
        kBinauralHighQuality,
        // Room effects only rendering.
        kRoomEffectsOnly,
    };

// Distance rolloff models used for distance attenuation.
    enum DistanceRolloffModel {
        // Logarithmic distance rolloff model.
        kLogarithmic = 0,
        // Linear distance rolloff model.
        kLinear,
        // Distance attenuation value will be explicitly set by the user.
        kNone,
    };

// Early reflection properties of an acoustic environment.
    struct ReflectionProperties {
        // Default constructor initializing all data members to 0.
        ReflectionProperties()
                : room_position{0.0f, 0.0f, 0.0f},
                  room_rotation{0.0f, 0.0f, 0.0f, 1.0f},
                  room_dimensions{0.0f, 0.0f, 0.0f},
                  cutoff_frequency(0.0f),
                  coefficients{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                  gain(0.0f) {}

        // Center position of the shoebox room in world space.
        float room_position[3];
        // Rotation (quaternion) of the shoebox room in world space.
        float room_rotation[4];
        // Size of the shoebox room in world space.
        float room_dimensions[3];
        // Frequency threshold for low pass filtering (-3dB cuttoff).
        float cutoff_frequency;
        // Reflection coefficients stored in world space.
        float coefficients[6];
        // Uniform reflections gain applied to all reflections.
        float gain;
    };

// Late reverberation properties of an acoustic environment.
    struct ReverbProperties {
        // Default constructor initializing all data members to 0.
        ReverbProperties()
                : rt60_values{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
                  gain(0.0f) {}

        // RT60's of the reverberation tail at different octave band centre
        // frequencies in seconds.
        float rt60_values[9];
        // Reverb gain.
        float gain;
    };

    class ResonanceAudioApi;

// Factory method to create a |ResonanceAudioApi| instance.
    extern "C" EXPORT_API ResonanceAudioApi* CreateResonanceAudioApi(
            size_t num_channels, size_t frames_per_buffer, int sample_rate_hz);

// The ResonanceAudioApi library renders high-quality spatial audio.
    class ResonanceAudioApi {
    public:
        // Sound object / ambisonic source identifier.
        typedef int SourceId;

        // Invalid source id.
        static const SourceId kInvalidSourceId = -1;

        virtual ~ResonanceAudioApi() {}

        // Renders and outputs an interleaved output buffer in float format.
        virtual bool FillInterleavedOutputBuffer(size_t num_channels,
                                                 size_t num_frames,
                                                 float* buffer_ptr) = 0;

        // Renders and outputs an interleaved output buffer in int16 format.
        virtual bool FillInterleavedOutputBuffer(size_t num_channels,
                                                 size_t num_frames,
                                                 int16* buffer_ptr) = 0;

        // Renders and outputs a planar output buffer in float format.
        virtual bool FillPlanarOutputBuffer(size_t num_channels, size_t num_frames,
                                            float* const* buffer_ptr) = 0;

        // Renders and outputs a planar output buffer in int16 format.
        virtual bool FillPlanarOutputBuffer(size_t num_channels, size_t num_frames,
                                            int16* const* buffer_ptr) = 0;

        // Sets listener's head position.
        virtual void SetHeadPosition(float x, float y, float z) = 0;

        // Sets listener's head rotation.
        virtual void SetHeadRotation(float x, float y, float z, float w) = 0;

        // Sets the master volume of the main audio output.
        virtual void SetMasterVolume(float volume) = 0;

        // Enables the stereo speaker mode.
        virtual void SetStereoSpeakerMode(bool enabled) = 0;

        // Creates an ambisonic source instance.
        virtual SourceId CreateAmbisonicSource(size_t num_channels) = 0;

        // Creates a stereo non-spatialized source instance.
        virtual SourceId CreateStereoSource(size_t num_channels) = 0;

        // Creates a sound object source instance.
        virtual SourceId CreateSoundObjectSource(RenderingMode rendering_mode) = 0;

        // Destroys source instance.
        virtual void DestroySource(SourceId id) = 0;

        // Sets the next audio buffer in interleaved float format to a sound source.
        virtual void SetInterleavedBuffer(SourceId source_id,
                                          const float* audio_buffer_ptr,
                                          size_t num_channels, size_t num_frames) = 0;

        // Sets the next audio buffer in interleaved int16 format to a sound source.
        virtual void SetInterleavedBuffer(SourceId source_id,
                                          const int16* audio_buffer_ptr,
                                          size_t num_channels, size_t num_frames) = 0;

        // Sets the next audio buffer in planar float format to a sound source.
        virtual void SetPlanarBuffer(SourceId source_id,
                                     const float* const* audio_buffer_ptr,
                                     size_t num_channels, size_t num_frames) = 0;

        // Sets the next audio buffer in planar int16 format to a sound source.
        virtual void SetPlanarBuffer(SourceId source_id,
                                     const int16* const* audio_buffer_ptr,
                                     size_t num_channels, size_t num_frames) = 0;

        // Sets the given source's distance attenuation value explicitly.
        virtual void SetSourceDistanceAttenuation(SourceId source_id,
                                                  float distance_attenuation) = 0;

        // Sets the given source's distance attenuation method.
        virtual void SetSourceDistanceModel(SourceId source_id,
                                            DistanceRolloffModel rolloff,
                                            float min_distance,
                                            float max_distance) = 0;

        // Sets the given source's position.
        virtual void SetSourcePosition(SourceId source_id, float x, float y,
                                       float z) = 0;

        // Sets the room effects contribution for the given source.
        virtual void SetSourceRoomEffectsGain(SourceId source_id,
                                              float room_effects_gain) = 0;

        // Sets the given source's rotation.
        virtual void SetSourceRotation(SourceId source_id, float x, float y, float z,
                                       float w) = 0;

        // Sets the given source's volume.
        virtual void SetSourceVolume(SourceId source_id, float volume) = 0;

        // Sets the given sound object source's directivity.
        virtual void SetSoundObjectDirectivity(SourceId sound_object_source_id,
                                               float alpha, float order) = 0;

        // Sets the listener's directivity with respect to the given sound object.
        virtual void SetSoundObjectListenerDirectivity(
                SourceId sound_object_source_id, float alpha, float order) = 0;

        // Sets the gain (linear) of the near field effect.
        virtual void SetSoundObjectNearFieldEffectGain(
                SourceId sound_object_source_id, float gain) = 0;

        // Sets the given sound object source's occlusion intensity.
        virtual void SetSoundObjectOcclusionIntensity(SourceId sound_object_source_id,
                                                      float intensity) = 0;

        // Sets the given sound object source's spread.
        virtual void SetSoundObjectSpread(SourceId sound_object_source_id,
                                          float spread_deg) = 0;

        // Turns on/off the reflections and reverberation.
        virtual void EnableRoomEffects(bool enable) = 0;

        // Sets the early reflection properties of the environment.
        virtual void SetReflectionProperties(
                const ReflectionProperties& reflection_properties) = 0;

        // Sets the late reverberation properties of the environment.
        virtual void SetReverbProperties(
                const ReverbProperties& reverb_properties) = 0;
    };

}  // namespace vraudio

#endif  // RESONANCE_AUDIO_API_RESONANCE_AUDIO_API_H_