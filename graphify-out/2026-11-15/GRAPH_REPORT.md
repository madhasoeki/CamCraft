# Graph Report - CamCraft  (2026-11-15)

## Corpus Check
- 74 files · ~39,477 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 17 file(s) not represented in the graph (top: .pftxt 7, .xml 3, (none) 2)

## Summary
- 393 nodes · 444 edges · 43 communities (26 shown, 17 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 45 edges (avg confidence: 0.91)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `e554c1f0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Modern CameraX APIs Reference
- Perfetto Trace Analysis & Profiling
- Rust Media Sink & Decode
- CameraX Core & Spatial Mapping
- Project Specs & Agent Workflow
- CCFT v1 Protocol & Control
- USB ADB & Wi-Fi Transport
- CamCraftScreen.kt
- camcraft-gui/src/main.rs
- Foldables Camera Posture Support
- Android UI & Screen Automation
- Monorepo Scaffolding & Execution Plan
- CamCraft MVP v0.1 Scope
- CamCraft Project Overview
- End-to-End Media Pipeline
- High-Resolution Video Streaming
- CamCraft Vibecoding Toolchain
- camcraft-protocol/src/lib.rs
- HTML Report Format
- Capabilities
- camcraft-core/src/lib.rs
- Rust Patterns
- to-spec/SKILL.md
- ML Kit Spatial Analysis & Coordinate Mapping
- CameraX Skill Guide
- ScaffoldingSmokeTest.kt
- Camera2Interop.Extender
- interface
- interface
- interface
- camcraft-core
- Hermetic Camera Testing Guide
- Wear OS Remote Viewfinder Guide
- EncoderStubs.kt
- CameraController.kt
- StreamConnectionService.kt

## God Nodes (most connected - your core abstractions)
1. `CameraX Skill Guide` - 16 edges
2. `Per-Candidate Root Cause Protocol` - 12 edges
3. `PacketType` - 11 edges
4. `Capabilities` - 11 edges
5. `HTML Report Format` - 8 edges
6. `Rust Patterns` - 8 edges
7. `PerfettoSQL Querying Guide` - 8 edges
8. `CoreError` - 7 edges
9. `v4l2loopback Output Sink (vcam)` - 7 edges
10. `CamCraftScreen()` - 6 edges

## Surprising Connections (you probably didn't know these)
- `Rust Zero Panic Production Rule` --conceptually_related_to--> `crates/camcraft-core`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Rust Unsafe FFI Invariant Rule` --conceptually_related_to--> `v4l2loopback Output Sink (vcam)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Rust Zero-Copy Hot Loop & Thread Isolation` --rationale_for--> `Bounded Video Queue Frame-Drop Strategy`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
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

## Communities (43 total, 17 thin omitted)

### Community 0 - "Modern CameraX APIs Reference"
Cohesion: 0.18
Nodes (11): Media3Effect Pipeline, RAW and JPEG Capture (DngCreator), Structural Blueprinting, AE-Mode Low Light Boost, Low-Light Capture Guide, Night Mode Extension (ExtensionSessionConfig), Google Play Services Low Light Boost (SurfaceProcessor), CameraControl Zoom APIs (+3 more)

### Community 1 - "Perfetto Trace Analysis & Profiling"
Cohesion: 0.08
Nodes (24): android CLI, android-cli Skill, android profiler CLI Subcommand, Analysis Orchestrator, PerfettoSQL Query Workflow, Perfetto Trace Analysis Workflow, CPU Scheduling & Frequency Hints, Graphics & UI Jank Hints (+16 more)

### Community 2 - "Rust Media Sink & Decode"
Cohesion: 0.08
Nodes (22): AudioCaptureEngine (Android encoder/), crates/camcraft-core, crates/camcraft-gui (ArtCraft Studio Dark UI), CameraController (Android camera/), Video Decode Worker (ffmpeg-next / VA-API), HardwareVideoEncoder (Android encoder/), PipeWire Virtual Audio Sink (vmic), v4l2loopback Output Sink (vcam) (+14 more)

### Community 3 - "CameraX Core & Spatial Mapping"
Cohesion: 0.25
Nodes (7): Camera1 to CameraX Migration Guide, CameraXViewfinder, ImageCapture, PreviewView, ProcessCameraProvider, Camera2 to CameraX Migration Guide, ImageAnalysis

### Community 4 - "Project Specs & Agent Workflow"
Cohesion: 0.27
Nodes (5): Vibecoding Agent Workflow Loop, ADB Port Forwarding, Justfile Task Runner, v4l2loopback Driver, setup-fedora-dev.sh script

### Community 5 - "CCFT v1 Protocol & Control"
Cohesion: 0.19
Nodes (12): crates/camcraft-protocol, Tahap 1: CCFT v1 Protocol Implementation, Tahap 5: Desktop GUI & Two-Way Camera Control, CCFT v1 Binary Header (20 Bytes Big-Endian), CONTROL_ACK (0x12) JSON Schema, CONTROL_CMD (0x11) Tagged Enum JSON, CONTROL_HELLO (0x10) JSON Schema, FrameFlags Bitmask (u16) (+4 more)

### Community 7 - "USB ADB & Wi-Fi Transport"
Cohesion: 0.22
Nodes (8): crates/camcraft-cli, StreamConnectionService (Android network/), Tahap 2: USB ADB Tunnel & Synthetic Ping-Pong, Tahap 6: Wi-Fi & mDNS Service Discovery, FR-02: USB ADB Ultra-Low Latency Connection, FR-03: Wi-Fi LAN & mDNS Discovery, Prompt 2.1: USB ADB Tunnel & Synthetic Streamer, Prompt 6.1: Wi-Fi mDNS Discovery & Keyframe Recovery

### Community 8 - "CamCraftScreen.kt"
Cohesion: 0.08
Nodes (5): MainActivity, ArtCraftColors, CamCraftScreen(), CamCraftTheme(), CamCraftUiState

### Community 9 - "camcraft-gui/src/main.rs"
Cohesion: 0.09
Nodes (13): Cli, Command, Connect, Status, apply_artcraft_dark_theme(), CamCraftApp, COLOR_ACCENT_BLUE, COLOR_BG_CARBON (+5 more)

### Community 10 - "Foldables Camera Posture Support"
Cohesion: 0.40
Nodes (5): Foldables Camera Guide, FoldingFeature Postures (FLAT, Tabletop, Book), Rear Display Mode (REAR_DISPLAY_STATE), ViewPort and UseCaseGroup Recalculation, ViewPort.Builder

### Community 11 - "Android UI & Screen Automation"
Cohesion: 0.67
Nodes (4): ADB Shell Input & Interaction Rules, android screen (Annotated Screenshot & Resolve), XML Journey Test Evaluation, android layout (UI Dump)

### Community 12 - "Monorepo Scaffolding & Execution Plan"
Cohesion: 0.50
Nodes (3): CamCraft Monorepo Structure, Tahap 0: Monorepo Scaffolding & Graphify Baseline, Prompt 0.1: Monorepo Scaffolding & Graphify Baseline

### Community 19 - "camcraft-protocol/src/lib.rs"
Cohesion: 0.08
Nodes (23): DEFAULT_PORT, empty_payload(), HEADER_SIZE, MAX_PAYLOAD_LEN, PacketType, AudioConfig, AudioFrame, ControlAck (+15 more)

### Community 20 - "HTML Report Format"
Cohesion: 0.10
Nodes (18): Call-graph collapse, Candidate card, Cross-section (good for layered shallowness), Diagram patterns, Hand-built boxes-and-arrows (when Mermaid's layout fights you), Header, HTML Report Format, Mass diagram (good for "interface as wide as implementation") (+10 more)

### Community 21 - "Capabilities"
Cohesion: 0.10
Nodes (19): Async Programming & Concurrency, Behavioral Traits, Capabilities, Do not use this skill when, Error Handling & Safety, Example Interactions, Instructions, Knowledge Base (+11 more)

### Community 22 - "camcraft-core/src/lib.rs"
Cohesion: 0.11
Nodes (8): AudioOutputSink, bounded_video_channel(), CoreError, Io, Protocol, Unimplemented, DEFAULT_VIDEO_QUEUE_CAPACITY, VideoOutputSink

### Community 23 - "Rust Patterns"
Cohesion: 0.22
Nodes (8): 1. Ownership and Borrowing, 2. Error Handling, 3. Option Patterns, 4. Unsafe Guidelines, 5. Testing, 6. Crate Organization, 7. Anti-Pattern Catalog, Rust Patterns

### Community 24 - "to-spec/SKILL.md"
Cohesion: 0.22
Nodes (8): Further Notes, Implementation Decisions, Out of Scope, Problem Statement, Process, Solution, Testing Decisions, User Stories

### Community 25 - "ML Kit Spatial Analysis & Coordinate Mapping"
Cohesion: 0.25
Nodes (5): MeteringPointFactory, ML Kit Spatial Analysis & Coordinate Mapping, MlKitAnalyzer (COORDINATE_SYSTEM_VIEW_REFERENCED), Spatial SDKs (ARCore / OpenXR / OEM Passthrough), XR, AR, and VR Camera Guide

### Community 26 - "CameraX Skill Guide"
Cohesion: 0.33
Nodes (3): PendingRecording, Recorder.Builder, CameraX Skill Guide

### Community 28 - "Camera2Interop.Extender"
Cohesion: 0.33
Nodes (4): Camera2Interop.Extender, StreamUseCase Optimization, Camera Thermal & Power Management Strategy, Camera2Interop

### Community 29 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 30 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 31 - "interface"
Cohesion: 0.33
Nodes (5): interface, display_name, short_description, policy, allow_implicit_invocation

### Community 33 - "camcraft-core"
Cohesion: 0.83
Nodes (4): camcraft-cli, camcraft-core, camcraft-gui, camcraft-protocol

### Community 35 - "Wear OS Remote Viewfinder Guide"
Cohesion: 0.67
Nodes (3): Circular Watch UI & Rotary Input, Wear OS Remote Viewfinder Guide, Wearable DataClient & MessageClient Sync

## Knowledge Gaps
- **64 isolated node(s):** `Connect`, `Status`, `Protocol`, `Io`, `Unimplemented` (+59 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 213 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `CameraX Skill Guide` connect `CameraX Skill Guide` to `Modern CameraX APIs Reference`, `Hermetic Camera Testing Guide`, `CameraX Core & Spatial Mapping`, `Wear OS Remote Viewfinder Guide`, `Foldables Camera Posture Support`, `ML Kit Spatial Analysis & Coordinate Mapping`, `Camera2Interop.Extender`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `Per-Candidate Root Cause Protocol` (e.g. with `CPU Scheduling & Frequency Hints` and `Graphics & UI Jank Hints`) actually correct?**
  _`Per-Candidate Root Cause Protocol` has 6 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Connect`, `Status`, `Protocol` to the rest of the system?**
  _64 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Perfetto Trace Analysis & Profiling` be split into smaller, more focused modules?**
  _Cohesion score 0.0784313725490196 - nodes in this community are weakly interconnected._
- **Why does `crates/camcraft-core` connect `Rust Media Sink & Decode` to `USB ADB & Wi-Fi Transport`?**
  _High betweenness centrality (0.008) - this node is a cross-community bridge._
- **Should `Rust Media Sink & Decode` be split into smaller, more focused modules?**
  _Cohesion score 0.08374384236453201 - nodes in this community are weakly interconnected._
- **Should `CamCraftScreen.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.08021390374331551 - nodes in this community are weakly interconnected._