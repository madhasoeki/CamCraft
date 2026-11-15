# Graph Report - CamCraft  (2026-11-15)

## Corpus Check
- Corpus is ~30,896 words - fits in a single context window. You may not need a graph.

## Summary
- 179 nodes · 216 edges · 19 communities (12 shown, 7 thin omitted)
- Extraction: 80% EXTRACTED · 20% INFERRED · 0% AMBIGUOUS · INFERRED: 44 edges (avg confidence: 0.92)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- CameraX Advanced & Low-Light APIs
- Perfetto Trace Analysis & Profiling
- Rust Media Sink & Decode
- CameraX Core & Spatial Mapping
- Project Specs & Agent Workflow
- CCFT v1 Protocol & Control
- USB ADB & Wi-Fi Transport
- Android CameraX MediaCodec Pipeline
- Android CLI & Perfetto Tooling
- Foldables Camera Posture Support
- Android UI & Screen Automation
- Monorepo Scaffolding & Execution Plan
- CamCraft MVP v0.1 Scope
- CamCraft Project Overview
- End-to-End Media Pipeline
- High-Resolution Video Streaming
- CamCraft Vibecoding Toolchain

## God Nodes (most connected - your core abstractions)
1. `CameraX Skill Guide` - 16 edges
2. `Per-Candidate Root Cause Protocol` - 12 edges
3. `PerfettoSQL Querying Guide` - 8 edges
4. `v4l2loopback Output Sink (vcam)` - 7 edges
5. `Camera1 to CameraX Migration Guide` - 6 edges
6. `Modern CameraX APIs Reference` - 6 edges
7. `crates/camcraft-core` - 6 edges
8. `Video Decode Worker (ffmpeg-next / VA-API)` - 6 edges
9. `Structural Blueprinting` - 5 edges
10. `Immutable Fluent Builder Pattern` - 5 edges

## Surprising Connections (you probably didn't know these)
- `Rust Zero Panic Production Rule` --conceptually_related_to--> `crates/camcraft-core`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Rust Unsafe FFI Invariant Rule` --conceptually_related_to--> `v4l2loopback Output Sink (vcam)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Rust Zero-Copy Hot Loop & Thread Isolation` --rationale_for--> `Bounded Video Queue Frame-Drop Strategy`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Android CameraX-to-MediaCodec Surface Pipeline` --rationale_for--> `HardwareVideoEncoder (Android encoder/)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md
- `Android Foreground Service Lifecycle Management` --rationale_for--> `StreamConnectionService (Android network/)`  [INFERRED]
  AGENTS.md → docs/DESIGN.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Perfetto Trace Root-Cause Analysis Pipeline** — _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_perfetto_trace_analysis_trace_analysis_workflow, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_triage_system_triage, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_per_candidate_analysis_per_candidate_protocol, _agents_skills_android_profiler_references_perfetto_sql_perfettosql_guide [EXTRACTED 1.00]
- **Subsystem Diagnostic Hints Taxonomy** — _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_cpu_cpu_scheduling_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_graphics_graphics_jank_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_io_io_storage_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_ipc_ipc_binder_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_memory_memory_lmk_hints, _agents_skills_android_profiler_analysis_workflows_perfetto_trace_analysis_references_hints_hints_power_power_battery_hints [EXTRACTED 1.00]
- **Android UI Inspection & Journey Verification Loop** — _agents_skills_android_cli_skill_ui_dump_layout, _agents_skills_android_cli_skill_annotated_screenshot, _agents_skills_android_cli_skill_adb_shell_input, _agents_skills_android_cli_skill_journey_test [EXTRACTED 1.00]
- **Viewfinder-to-Sensor Coordinate Mapping System** — _agents_skills_camerax_references_camera1_to_camerax_meteringpointfactory, _agents_skills_camerax_references_foldables_viewport_usecasegroup, _agents_skills_camerax_references_mlkit_spatial_mlkitanalyzer, _agents_skills_camerax_references_mlkit_spatial_double_rotation_and_mirroring [INFERRED 0.85]
- **Camera2Interop Low-Level Hardware Control Pattern** — _agents_skills_camerax_skill_camera2interop, _agents_skills_camerax_references_camera2_to_camerax_camera2interop_extender, _agents_skills_camerax_references_expert_blueprints_manual_controls_blueprint, _agents_skills_camerax_references_thermals_streamusecase [INFERRED 0.95]
- **CameraX Computational Photography & Surface Effects Pipeline** — _agents_skills_camerax_references_low_light_night_mode_extension, _agents_skills_camerax_references_low_light_ae_low_light_boost, _agents_skills_camerax_references_low_light_play_services_llb, _agents_skills_camerax_references_expert_blueprints_media3_effects_pipeline [INFERRED 0.85]
- **CCFT v1 Binary & JSON Wire Protocol** — docs_protocol_ccft_header, docs_protocol_packet_type, docs_protocol_frame_flags, docs_protocol_control_hello, docs_protocol_control_cmd, docs_protocol_control_ack, docs_protocol_heartbeat [EXTRACTED 1.00]
- **End-to-End 60fps Hardware Video Streaming Pipeline** — docs_design_cameracontroller, docs_design_hardwarevideoencoder, docs_design_streamconnectionservice, docs_design_bounded_video_queue, docs_design_ffmpeg_decoder, docs_design_v4l2loopback_sink [EXTRACTED 1.00]
- **Two-Way Camera Control & State Sync Loop** — docs_design_camcraft_gui_crate, docs_protocol_control_cmd, docs_protocol_control_ack, docs_design_cameracontroller [INFERRED 0.95]

## Communities (19 total, 7 thin omitted)

### Community 0 - "CameraX Advanced & Low-Light APIs"
Cohesion: 0.08
Nodes (22): Camera2Interop.Extender, Media3Effect Pipeline, RAW and JPEG Capture (DngCreator), Structural Blueprinting, PendingRecording, Recorder.Builder, AE-Mode Low Light Boost, Low-Light Capture Guide (+14 more)

### Community 1 - "Perfetto Trace Analysis & Profiling"
Cohesion: 0.10
Nodes (20): Analysis Orchestrator, PerfettoSQL Query Workflow, Perfetto Trace Analysis Workflow, CPU Scheduling & Frequency Hints, Graphics & UI Jank Hints, I/O & Storage Bottleneck Hints, IPC & Binder Contention Hints, Memory Pressure & LMK Hints (+12 more)

### Community 2 - "Rust Media Sink & Decode"
Cohesion: 0.11
Nodes (17): AudioCaptureEngine (Android encoder/), crates/camcraft-core, crates/camcraft-gui (ArtCraft Studio Dark UI), Video Decode Worker (ffmpeg-next / VA-API), PipeWire Virtual Audio Sink (vmic), v4l2loopback Output Sink (vcam), Tahap 4: H.264 Decode & v4l2loopback Injection, Tahap 7: Opus Audio Streaming & PipeWire Virtual Mic (+9 more)

### Community 3 - "CameraX Core & Spatial Mapping"
Cohesion: 0.12
Nodes (12): Camera1 to CameraX Migration Guide, CameraXViewfinder, ImageCapture, MeteringPointFactory, PreviewView, ProcessCameraProvider, Camera2 to CameraX Migration Guide, ImageAnalysis (+4 more)

### Community 4 - "Project Specs & Agent Workflow"
Cohesion: 0.27
Nodes (5): Vibecoding Agent Workflow Loop, ADB Port Forwarding, Justfile Task Runner, v4l2loopback Driver, setup-fedora-dev.sh script

### Community 5 - "CCFT v1 Protocol & Control"
Cohesion: 0.19
Nodes (12): crates/camcraft-protocol, Tahap 1: CCFT v1 Protocol Implementation, Tahap 5: Desktop GUI & Two-Way Camera Control, CCFT v1 Binary Header (20 Bytes Big-Endian), CONTROL_ACK (0x12) JSON Schema, CONTROL_CMD (0x11) Tagged Enum JSON, CONTROL_HELLO (0x10) JSON Schema, FrameFlags Bitmask (u16) (+4 more)

### Community 7 - "USB ADB & Wi-Fi Transport"
Cohesion: 0.22
Nodes (8): crates/camcraft-cli, StreamConnectionService (Android network/), Tahap 2: USB ADB Tunnel & Synthetic Ping-Pong, Tahap 6: Wi-Fi & mDNS Service Discovery, FR-02: USB ADB Ultra-Low Latency Connection, FR-03: Wi-Fi LAN & mDNS Discovery, Prompt 2.1: USB ADB Tunnel & Synthetic Streamer, Prompt 6.1: Wi-Fi mDNS Discovery & Keyframe Recovery

### Community 8 - "Android CameraX MediaCodec Pipeline"
Cohesion: 0.33
Nodes (5): CameraController (Android camera/), HardwareVideoEncoder (Android encoder/), Tahap 3: CameraX + MediaCodec H.264 Pipeline, Non-Functional Performance & Stability Targets, Prompt 3.1: CameraX & MediaCodec H.264 Surface Encoder

### Community 9 - "Android CLI & Perfetto Tooling"
Cohesion: 0.40
Nodes (4): android CLI, android-cli Skill, android profiler CLI Subcommand, Perfetto Helper Scripts

### Community 10 - "Foldables Camera Posture Support"
Cohesion: 0.40
Nodes (5): Foldables Camera Guide, FoldingFeature Postures (FLAT, Tabletop, Book), Rear Display Mode (REAR_DISPLAY_STATE), ViewPort and UseCaseGroup Recalculation, ViewPort.Builder

### Community 11 - "Android UI & Screen Automation"
Cohesion: 0.67
Nodes (4): ADB Shell Input & Interaction Rules, android screen (Annotated Screenshot & Resolve), XML Journey Test Evaluation, android layout (UI Dump)

### Community 12 - "Monorepo Scaffolding & Execution Plan"
Cohesion: 0.50
Nodes (3): CamCraft Monorepo Structure, Tahap 0: Monorepo Scaffolding & Graphify Baseline, Prompt 0.1: Monorepo Scaffolding & Graphify Baseline

## Knowledge Gaps
- **46 isolated node(s):** `android-cli Skill`, `SPAN_JOIN Materialization & Partitioning`, `PerfettoSQL Standard Library Modules`, `Power, Battery & Wakelock Hints`, `PreviewView` (+41 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 78 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **7 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `CameraX Skill Guide` connect `CameraX Advanced & Low-Light APIs` to `Foldables Camera Posture Support`, `CameraX Core & Spatial Mapping`?**
  _High betweenness centrality (0.069) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `Per-Candidate Root Cause Protocol` (e.g. with `CPU Scheduling & Frequency Hints` and `Graphics & UI Jank Hints`) actually correct?**
  _`Per-Candidate Root Cause Protocol` has 6 INFERRED edges - model-reasoned connections that need verification._
- **What connects `android-cli Skill`, `SPAN_JOIN Materialization & Partitioning`, `PerfettoSQL Standard Library Modules` to the rest of the system?**
  _46 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `CameraX Advanced & Low-Light APIs` be split into smaller, more focused modules?**
  _Cohesion score 0.07956989247311828 - nodes in this community are weakly interconnected._
- **Why does `Video Decode Worker (ffmpeg-next / VA-API)` connect `Rust Media Sink & Decode` to `Android CameraX MediaCodec Pipeline`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `v4l2loopback Output Sink (vcam)` (e.g. with `Rust Unsafe FFI Invariant Rule` and `FR-04: Linux Virtual Camera (v4l2loopback)`) actually correct?**
  _`v4l2loopback Output Sink (vcam)` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Should `Perfetto Trace Analysis & Profiling` be split into smaller, more focused modules?**
  _Cohesion score 0.0960591133004926 - nodes in this community are weakly interconnected._