# Graph Report - CamCraft  (2026-11-16)

## Corpus Check
- 45 files · ~60,376 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 17 file(s) not represented in the graph (top: .pftxt 7, .xml 3, (none) 2)

## Summary
- 952 nodes · 1569 edges · 77 communities (44 shown, 33 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 63 edges (avg confidence: 0.87)
- Token cost: 1,500 input · 800 output

## Graph Freshness
- Built from commit: `ac1a4b69`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Android Camera Enumeration
- Android CCFT Packet Framing
- Rust H.264 Video Decoder
- Android CameraX Stream Manager
- Android CLI & Profiling Tools
- Rust CCFT Serde & Models
- CCFT Frame Flags Bitmask
- Android Jetpack Compose UI
- Linux V4L2 Virtual Camera Sink
- Android Protocol Constants
- Android MediaCodec H.264 Encoder
- ControlModels.kt
- Architecture Review Guidelines
- Desktop Native GUI Preview
- client.rs
- Subsystem 15
- Subsystem 16
- vcam.rs
- Subsystem 19
- Subsystem 20
- Subsystem 21
- Subsystem 22
- Subsystem 23
- Subsystem 25
- Subsystem 26
- Subsystem 27
- Subsystem 28
- Subsystem 30
- Subsystem 31
- Subsystem 32
- Subsystem 33
- Subsystem 34
- Subsystem 35
- Subsystem 37
- Subsystem 38
- Subsystem 39
- Subsystem 40
- Subsystem 41
- Subsystem 42
- Subsystem 43
- Subsystem 44
- Subsystem 45
- Subsystem 46
- Subsystem 47
- Subsystem 48
- Subsystem 49
- Subsystem 50
- Subsystem 51
- Subsystem 52
- Subsystem 53
- Subsystem 54
- Subsystem 56
- Subsystem 57
- Subsystem 58
- Subsystem 59
- Subsystem 61
- Subsystem 63
- Subsystem 65
- Subsystem 75
- Subsystem 76
- Subsystem 77

## God Nodes (most connected - your core abstractions)
1. `FrameFlags` - 35 edges
2. `CamCraftStreamService` - 31 edges
3. `CameraStreamManager` - 29 edges
4. `CcftPacket` - 23 edges
5. `VcamError` - 21 edges
6. `DecoderError` - 20 edges
7. `Packet` - 20 edges
8. `PacketType` - 17 edges
9. `PacketType` - 17 edges
10. `ProtocolError` - 17 edges

## Surprising Connections (you probably didn't know these)
- `Rust Unsafe FFI Invariant Rule` --conceptually_related_to--> `v4l2loopback Output Sink (vcam)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Rust Zero Panic Production Rule` --conceptually_related_to--> `crates/camcraft-core`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `V4l2LoopbackSink` --references--> `Device`  [INFERRED]
  desktop/crates/camcraft-core/src/vcam.rs → desktop/crates/camcraft-core/src/adb.rs
- `Android Foreground Service Lifecycle Management` --rationale_for--> `StreamConnectionService (Android network/)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Android CameraX-to-MediaCodec Surface Pipeline` --rationale_for--> `HardwareVideoEncoder (Android encoder/)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Subsystem Diagnostic Hints Taxonomy** — _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_cpu_cpu_scheduling_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_graphics_graphics_jank_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_io_io_storage_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_ipc_ipc_binder_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_memory_memory_lmk_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_power_power_battery_hints [EXTRACTED 1.00]
- **Android UI Inspection & Journey Verification Loop** — _agents_skills_android_cli_skill_ui_dump_layout, _agents_skills_android_cli_skill_annotated_screenshot, _agents_skills_android_cli_skill_adb_shell_input, _agents_skills_android_cli_skill_journey_test [EXTRACTED 1.00]
- **CCFT v1 Binary & JSON Wire Protocol** — docs_protocol_ccft_header, docs_protocol_packet_type, docs_protocol_frame_flags, docs_protocol_control_hello, docs_protocol_control_cmd, docs_protocol_control_ack, docs_protocol_heartbeat [EXTRACTED 1.00]
- **Perfetto Trace Root-Cause Analysis Pipeline** — _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_perfetto_trace_analysis_trace_analysis_workflow, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_triage_system_triage, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_per_candidate_analysis_per_candidate_protocol, _agents_skills_android_profiler_references_perfetto_sql_perfettosql_guide [EXTRACTED 1.00]
- **End-to-End 60fps Hardware Video Streaming Pipeline** — docs_design_cameracontroller, docs_design_hardwarevideoencoder, docs_design_streamconnectionservice, docs_design_bounded_video_queue, docs_design_ffmpeg_decoder, docs_design_v4l2loopback_sink [EXTRACTED 1.00]
- **Viewfinder-to-Sensor Coordinate Mapping System** — _agents_skills_camerax_references_camera1_to_camerax_meteringpointfactory, _agents_skills_camerax_references_foldables_viewport_usecasegroup, _agents_skills_camerax_references_mlkit_spatial_mlkitanalyzer, _agents_skills_camerax_references_mlkit_spatial_double_rotation_and_mirroring [INFERRED 0.85]
- **CameraX Computational Photography & Surface Effects Pipeline** — _agents_skills_camerax_references_low_light_night_mode_extension, _agents_skills_camerax_references_low_light_ae_low_light_boost, _agents_skills_camerax_references_low_light_play_services_llb, _agents_skills_camerax_references_expert_blueprints_media3_effects_pipeline [INFERRED 0.85]
- **Camera2Interop Low-Level Hardware Control Pattern** — _agents_skills_camerax_skill_camera2interop, _agents_skills_camerax_references_camera2_to_camerax_camera2interop_extender, _agents_skills_camerax_references_expert_blueprints_manual_controls_blueprint, _agents_skills_camerax_references_thermals_streamusecase [INFERRED 0.95]
- **Two-Way Camera Control & State Sync Loop** — docs_design_camcraft_gui_crate, docs_protocol_control_cmd, docs_protocol_control_ack, docs_design_cameracontroller [INFERRED 0.95]

## Communities (77 total, 33 thin omitted)

### Community 0 - "Android Camera Enumeration"
Cohesion: 0.05
Nodes (4): CamCraftStreamService, defaultActiveCameraState(), StreamServiceState, CamCraftStreamServiceTest

### Community 1 - "Android CCFT Packet Framing"
Cohesion: 0.06
Nodes (14): CcftHeader, PacketType, AUDIO_CONFIG, AUDIO_FRAME, CONTROL_ACK, CONTROL_CMD, CONTROL_HELLO, HEARTBEAT_PING (+6 more)

### Community 2 - "Rust H.264 Video Decoder"
Cohesion: 0.08
Nodes (22): bounded_video_channel(), DecodedFrame, DECODER_QUEUE_CAPACITY, DecoderCommand, Config, Flush, Frame, Stop (+14 more)

### Community 4 - "Android CLI & Profiling Tools"
Cohesion: 0.08
Nodes (24): android CLI, android-cli Skill, android profiler CLI Subcommand, Analysis Orchestrator, PerfettoSQL Query Workflow, Perfetto Trace Analysis Workflow, CPU Scheduling & Frequency Hints, Graphics & UI Jank Hints (+16 more)

### Community 5 - "Rust CCFT Serde & Models"
Cohesion: 0.07
Nodes (25): ActiveCameraState, AudioConfigPayload, CameraInfo, CANONICAL_TEST_FRAME, control_ack_json_schema_matches_spec(), ControlAck, ControlCommand, ControlHello (+17 more)

### Community 6 - "CCFT Frame Flags Bitmask"
Cohesion: 0.08
Nodes (10): frame_flags_operations_and_rotations(), FrameFlags, .CODEC_HEVC, .KEYFRAME, .MIRROR_H, .NONE, .ROTATE_180, .ROTATE_270 (+2 more)

### Community 8 - "Linux V4L2 Virtual Camera Sink"
Cohesion: 0.12
Nodes (10): test_v4l2_loopback_sink_lifecycle(), V4l2LoopbackSink, VcamError, DeviceOpen, FormatError, InvalidCapabilities, Io, NotOpen (+2 more)

### Community 11 - "ControlModels.kt"
Cohesion: 0.15
Nodes (12): CameraEnumerator, ActiveCameraState, AudioConfigPayload, CameraInfo, ConfigureStream, ControlAck, ControlCmd, ControlHello (+4 more)

### Community 12 - "Architecture Review Guidelines"
Cohesion: 0.10
Nodes (11): Grill Me Skill, Relentless Grilling Interview, HTML Report Format Guide, Candidate Card Specification, Self-Contained HTML Architecture Report Scaffold, Improve Codebase Architecture Skill, Module Deepening Opportunities, Candidate Grilling Decision Loop (+3 more)

### Community 13 - "Desktop Native GUI Preview"
Cohesion: 0.14
Nodes (9): apply_artcraft_dark_theme(), CamCraftApp, COLOR_ACCENT_BLUE, COLOR_BG_CARBON, COLOR_STATUS_EMERALD, COLOR_SURFACE_CARD, COLOR_TEXT_MUTED, COLOR_TEXT_PRIMARY (+1 more)

### Community 14 - "client.rs"
Cohesion: 0.08
Nodes (24): print_stats(), ClientConfig, ClientError, Channel, ConnectionClosed, Io, Json, Protocol (+16 more)

### Community 15 - "Subsystem 15"
Cohesion: 0.09
Nodes (8): Cli, Command, Connect, Status, ConnectArgs, main(), print_device_banner(), run_connect()

### Community 16 - "Subsystem 16"
Cohesion: 0.09
Nodes (23): AdbDevice, AdbDeviceState, Connecting, Device, Offline, Other, Unauthorized, AdbError (+15 more)

### Community 17 - "vcam.rs"
Cohesion: 0.20
Nodes (5): find_camcraft_v4l2_device(), generate_standby_frame(), test_auto_detect_device(), test_standby_frame_size(), test_standby_pattern_generation()

### Community 19 - "Subsystem 19"
Cohesion: 0.15
Nodes (12): PacketHeader, PacketType, AudioConfig, AudioFrame, ControlAck, ControlCmd, ControlHello, HeartbeatPing (+4 more)

### Community 20 - "Subsystem 20"
Cohesion: 0.29
Nodes (3): control_hello_json_schema_matches_spec(), multiple_packets_in_single_buffer(), Packet

### Community 21 - "Subsystem 21"
Cohesion: 0.15
Nodes (9): Rust Patterns Skill, Rust Anti-Pattern Catalog, Cargo Multi-Crate Workspace Architecture, Option Type & Combinator Idioms, Unit, Integration & Property Testing Discipline, Rust Pro (2024 Edition) Skill, Modern Rust 2024 Language Features, Production Error Handling Architecture (+1 more)

### Community 22 - "Subsystem 22"
Cohesion: 0.27
Nodes (5): Vibecoding Agent Workflow Loop, ADB Port Forwarding, Justfile Task Runner, v4l2loopback Driver, setup-fedora-dev.sh script

### Community 23 - "Subsystem 23"
Cohesion: 0.18
Nodes (11): Media3Effect Pipeline, RAW and JPEG Capture (DngCreator), Structural Blueprinting, AE-Mode Low Light Boost, Low-Light Capture Guide, Night Mode Extension (ExtensionSessionConfig), Google Play Services Low Light Boost (SurfaceProcessor), CameraControl Zoom APIs (+3 more)

### Community 25 - "Subsystem 25"
Cohesion: 0.26
Nodes (7): ProtocolError, BufferTooShort, InvalidMagic, Json, PayloadTooLarge, UnknownPacketType, UnsupportedVersion

### Community 27 - "Subsystem 27"
Cohesion: 0.18
Nodes (9): CoreError, Adb, Client, Decoder, Io, Protocol, Unimplemented, Vcam (+1 more)

### Community 28 - "Subsystem 28"
Cohesion: 0.24
Nodes (10): crates/camcraft-protocol, Tahap 1: CCFT v1 Protocol Implementation, CCFT v1 Binary Header (20 Bytes Big-Endian), CONTROL_ACK (0x12) JSON Schema, CONTROL_CMD (0x11) Tagged Enum JSON, CONTROL_HELLO (0x10) JSON Schema, FrameFlags Bitmask (u16), HEARTBEAT_PING (0xFE) / PONG (0xFF) (+2 more)

### Community 31 - "Subsystem 31"
Cohesion: 0.25
Nodes (7): Camera1 to CameraX Migration Guide, CameraXViewfinder, ImageCapture, PreviewView, ProcessCameraProvider, Camera2 to CameraX Migration Guide, ImageAnalysis

### Community 32 - "Subsystem 32"
Cohesion: 0.22
Nodes (8): crates/camcraft-cli, StreamConnectionService (Android network/), Tahap 2: USB ADB Tunnel & Synthetic Ping-Pong, Tahap 6: Wi-Fi & mDNS Service Discovery, FR-02: USB ADB Ultra-Low Latency Connection, FR-03: Wi-Fi LAN & mDNS Discovery, Prompt 2.1: USB ADB Tunnel & Synthetic Streamer, Prompt 6.1: Wi-Fi mDNS Discovery & Keyframe Recovery

### Community 33 - "Subsystem 33"
Cohesion: 0.25
Nodes (6): BufferTooShort, CcftProtocolException, InvalidMagic, PayloadTooLarge, UnknownPacketType, UnsupportedVersion

### Community 34 - "Subsystem 34"
Cohesion: 0.28
Nodes (6): control_cmd_variants_serialize_and_deserialize(), ControlCmd, ConfigureStream, RequestKeyframe, SetAudioState, SetCameraParams

### Community 35 - "Subsystem 35"
Cohesion: 0.25
Nodes (5): MeteringPointFactory, ML Kit Spatial Analysis & Coordinate Mapping, MlKitAnalyzer (COORDINATE_SYSTEM_VIEW_REFERENCED), Spatial SDKs (ARCore / OpenXR / OEM Passthrough), XR, AR, and VR Camera Guide

### Community 37 - "Subsystem 37"
Cohesion: 0.33
Nodes (3): PendingRecording, Recorder.Builder, CameraX Skill Guide

### Community 38 - "Subsystem 38"
Cohesion: 0.33
Nodes (5): CameraController (Android camera/), HardwareVideoEncoder (Android encoder/), Tahap 3: CameraX + MediaCodec H.264 Pipeline, Non-Functional Performance & Stability Targets, Prompt 3.1: CameraX & MediaCodec H.264 Surface Encoder

### Community 39 - "Subsystem 39"
Cohesion: 0.29
Nodes (6): v4l2loopback Output Sink (vcam), Tahap 8: OLED Saver, Auto-Reconnect & v0.1 Polish, FR-04: Linux Virtual Camera (v4l2loopback), FR-07: OLED Battery Saver / Dim Screen, FR-08: Auto-Reconnect & Heartbeat, Prompt 8.1: OLED Battery Saver, Auto-Reconnect & Audit

### Community 41 - "Subsystem 41"
Cohesion: 0.38
Nodes (5): canonical_hex_vector_decode_and_encode(), decode(), encode(), partial_tcp_stream_chunking_byte_by_byte(), protocol_header_error_cases()

### Community 42 - "Subsystem 42"
Cohesion: 0.29
Nodes (7): crates/camcraft-gui (ArtCraft Studio Dark UI), Video Decode Worker (ffmpeg-next / VA-API), Tahap 4: H.264 Decode & v4l2loopback Injection, Tahap 5: Desktop GUI & Two-Way Camera Control, FR-05: Two-Way Camera Controls, Prompt 4.1: H.264 Decoder & v4l2loopback Sink, Prompt 5.1: Desktop GUI & Two-Way Camera Control

### Community 43 - "Subsystem 43"
Cohesion: 0.33
Nodes (4): Camera2Interop.Extender, StreamUseCase Optimization, Camera Thermal & Power Management Strategy, Camera2Interop

### Community 44 - "Subsystem 44"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 45 - "Subsystem 45"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 46 - "Subsystem 46"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 47 - "Subsystem 47"
Cohesion: 0.33
Nodes (5): Run Workflow Dynamic Agent Orchestration Skill, Stateful Conversational Agent Chains, Dynamic Python Workflow Scripting, Agent Orchestration Primitives (phase, agent, parallel, pipeline), Conversation Trajectory Step Inspection

### Community 48 - "Subsystem 48"
Cohesion: 0.40
Nodes (5): Foldables Camera Guide, FoldingFeature Postures (FLAT, Tabletop, Book), Rear Display Mode (REAR_DISPLAY_STATE), ViewPort and UseCaseGroup Recalculation, ViewPort.Builder

### Community 51 - "Subsystem 51"
Cohesion: 0.40
Nodes (5): StreamConnectionStatus, CONNECTED, ERROR, LISTENING, STOPPED

### Community 52 - "Subsystem 52"
Cohesion: 0.50
Nodes (5): AudioCaptureEngine (Android encoder/), PipeWire Virtual Audio Sink (vmic), Tahap 7: Opus Audio Streaming & PipeWire Virtual Mic, FR-06: Audio Mic Forwarding & PipeWire Virtual Mic, Prompt 7.1: Audio Capture & PipeWire Virtual Mic

### Community 53 - "Subsystem 53"
Cohesion: 0.67
Nodes (4): ADB Shell Input & Interaction Rules, android screen (Annotated Screenshot & Resolve), XML Journey Test Evaluation, android layout (UI Dump)

### Community 54 - "Subsystem 54"
Cohesion: 0.50
Nodes (3): CamCraft Monorepo Structure, Tahap 0: Monorepo Scaffolding & Graphify Baseline, Prompt 0.1: Monorepo Scaffolding & Graphify Baseline

### Community 56 - "Subsystem 56"
Cohesion: 0.83
Nodes (4): camcraft-cli, camcraft-core, camcraft-gui, camcraft-protocol

### Community 58 - "Subsystem 58"
Cohesion: 0.67
Nodes (3): Circular Watch UI & Rotary Input, Wear OS Remote Viewfinder Guide, Wearable DataClient & MessageClient Sync

## Knowledge Gaps
- **131 isolated node(s):** `STOPPED`, `LISTENING`, `CONNECTED`, `ERROR`, `Connect` (+126 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 385 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `PacketHeader` connect `Subsystem 19` to `Rust CCFT Serde & Models`, `CCFT Frame Flags Bitmask`, `client.rs`, `Subsystem 20`, `Subsystem 25`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **What connects `STOPPED`, `LISTENING`, `CONNECTED` to the rest of the system?**
  _131 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Android Camera Enumeration` be split into smaller, more focused modules?**
  _Cohesion score 0.05446853516657853 - nodes in this community are weakly interconnected._
- **Why does `CcftPacket` connect `Android CCFT Packet Framing` to `Android Camera Enumeration`, `ControlModels.kt`?**
  _High betweenness centrality (0.045) - this node is a cross-community bridge._
- **Should `Android CCFT Packet Framing` be split into smaller, more focused modules?**
  _Cohesion score 0.06041986687147977 - nodes in this community are weakly interconnected._
- **Why does `CamCraftStreamService` connect `Android Camera Enumeration` to `Android CameraX Stream Manager`, `Android Jetpack Compose UI`, `Subsystem 40`, `ControlModels.kt`, `Subsystem 50`, `Subsystem 29`?**
  _High betweenness centrality (0.045) - this node is a cross-community bridge._
- **Should `Rust H.264 Video Decoder` be split into smaller, more focused modules?**
  _Cohesion score 0.07982583454281568 - nodes in this community are weakly interconnected._