
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

#if !defined(EXPORT_API)
#define EXPORT_API
#endif

#include <cstddef>
#include <cstdint>

typedef int16_t int16;

namespace vraudio {

enum RenderingMode {
  kStereoPanning = 0,
  kBinauralLowQuality,
  kBinauralMediumQuality,
  kBinauralHighQuality,
  kRoomEffectsOnly,
};

enum DistanceRolloffModel {
  kLogarithmic = 0,
  kLinear,
  kNone,
};

struct ReflectionProperties {
  ReflectionProperties()
      : room_position{0.0f, 0.0f, 0.0f},
        room_rotation{0.0f, 0.0f, 0.0f, 1.0f},
        room_dimensions{0.0f, 0.0f, 0.0f},
        cutoff_frequency(0.0f),
        coefficients{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        gain(0.0f) {}

  float room_position[3];
  float room_rotation[4];
  float room_dimensions[3];
  float cutoff_frequency;
  float coefficients[6];
  float gain;
};

struct ReverbProperties {
  ReverbProperties()
      : rt60_values{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        gain(0.0f) {}

  float rt60_values[9];
  float gain;
};

class ResonanceAudioApi;

extern "C" EXPORT_API ResonanceAudioApi* CreateResonanceAudioApi(
    size_t num_channels, size_t frames_per_buffer, int sample_rate_hz);

class ResonanceAudioApi {
 public:
  typedef int SourceId;
  static const SourceId kInvalidSourceId = -1;

  virtual ~ResonanceAudioApi() {}

  virtual bool FillInterleavedOutputBuffer(size_t num_channels,
                                           size_t num_frames,
                                           float* buffer_ptr) = 0;

  virtual bool FillInterleavedOutputBuffer(size_t num_channels,
                                           size_t num_frames,
                                           int16* buffer_ptr) = 0;

  virtual bool FillPlanarOutputBuffer(size_t num_channels, size_t num_frames,
                                      float* const* buffer_ptr) = 0;

  virtual bool FillPlanarOutputBuffer(size_t num_channels, size_t num_frames,
                                      int16* const* buffer_ptr) = 0;

  virtual void SetHeadPosition(float x, float y, float z) = 0;
  virtual void SetHeadRotation(float x, float y, float z, float w) = 0;
  virtual void SetMasterVolume(float volume) = 0;
  virtual void SetStereoSpeakerMode(bool enabled) = 0;
  virtual SourceId CreateAmbisonicSource(size_t num_channels) = 0;
  virtual SourceId CreateStereoSource(size_t num_channels) = 0;
  virtual SourceId CreateSoundObjectSource(RenderingMode rendering_mode) = 0;
  virtual void DestroySource(SourceId id) = 0;

  virtual void SetInterleavedBuffer(SourceId source_id,
                                    const float* audio_buffer_ptr,
                                    size_t num_channels, size_t num_frames) = 0;

  virtual void SetInterleavedBuffer(SourceId source_id,
                                    const int16* audio_buffer_ptr,
                                    size_t num_channels, size_t num_frames) = 0;

  virtual void SetPlanarBuffer(SourceId source_id,
                               const float* const* audio_buffer_ptr,
                               size_t num_channels, size_t num_frames) = 0;

  virtual void SetPlanarBuffer(SourceId source_id,
                               const int16* const* audio_buffer_ptr,
                               size_t num_channels, size_t num_frames) = 0;

  virtual void SetSourceDistanceAttenuation(SourceId source_id,
                                            float distance_attenuation) = 0;

  virtual void SetSourceDistanceModel(SourceId source_id,
                                      DistanceRolloffModel rolloff,
                                      float min_distance,
                                      float max_distance) = 0;

  virtual void SetSourcePosition(SourceId source_id, float x, float y,
                                 float z) = 0;

  virtual void SetSourceRoomEffectsGain(SourceId source_id,
                                        float room_effects_gain) = 0;

  virtual void SetSourceRotation(SourceId source_id, float x, float y, float z,
                                 float w) = 0;

  virtual void SetSourceVolume(SourceId source_id, float volume) = 0;

  virtual void SetSoundObjectDirectivity(SourceId sound_object_source_id,
                                         float alpha, float order) = 0;

  virtual void SetSoundObjectListenerDirectivity(
      SourceId sound_object_source_id, float alpha, float order) = 0;

  virtual void SetSoundObjectNearFieldEffectGain(
      SourceId sound_object_source_id, float gain) = 0;

  virtual void SetSoundObjectOcclusionIntensity(SourceId sound_object_source_id,
                                                float intensity) = 0;

  virtual void SetSoundObjectSpread(SourceId sound_object_source_id,
                                    float spread_deg) = 0;

  virtual void EnableRoomEffects(bool enable) = 0;

  virtual void SetReflectionProperties(
      const ReflectionProperties& reflection_properties) = 0;

  virtual void SetReverbProperties(
      const ReverbProperties& reverb_properties) = 0;
};

}  // namespace vraudio

#endif  // RESONANCE_AUDIO_API_RESONANCE_AUDIO_API_H_
