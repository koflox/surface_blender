## Surface blender

Synchronized decoder/surface_transformer/encoder with EGL/OpenGL/MediaCodec

#### High level components relationship

MediaCodec (set up as decoder) -> Surface transformer (EGL manupulates Surface via OpenGL) -> MediaCodec (set up as encoder)

#### TODO
 - Performance optimnizations
   - 3 threads are being blocked, one pre decoder, transformer and encoder. One thread refactoring is needed.
