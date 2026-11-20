use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use bytes::BytesMut;
use camcraft_core::client::{ClientConfig, ClientEvent, run_client_loop};
use camcraft_protocol::{
    ActiveCameraState, CameraInfo, ControlHello, Packet, PacketType, ResolutionOption,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;

#[tokio::test]
async fn test_client_receiver_end_to_end_mock_stream() {
    // Bind to an arbitrary available OS port
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let local_addr = listener.local_addr().unwrap();
    let endpoint = format!("127.0.0.1:{}", local_addr.port());

    let server_shutdown = Arc::new(AtomicBool::new(false));
    let server_shutdown_clone = server_shutdown.clone();

    // Spawn mock Android sender
    let server_handle = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        socket.set_nodelay(true).unwrap();

        // 1. Send CONTROL_HELLO
        let hello = ControlHello {
            device_name: "Mock Pixel Phone".to_string(),
            protocol_version: 1,
            cameras: vec![CameraInfo {
                id: "0".to_string(),
                facing: "back".to_string(),
                label: "Rear Main Wide".to_string(),
                resolutions: vec![ResolutionOption {
                    width: 1920,
                    height: 1080,
                    max_fps: 60,
                }],
                max_zoom: 8.0,
                ev_range: (-4.0, 4.0),
                has_flash: true,
            }],
            active_state: ActiveCameraState {
                camera_id: "0".to_string(),
                width: 1920,
                height: 1080,
                fps: 60,
                bitrate_kbps: 12000,
                zoom_ratio: 1.0,
                exposure_ev: 0.0,
                autofocus: true,
                torch_on: false,
                mirror_horizontal: false,
                rotation_deg: 0,
                audio_enabled: true,
                audio_muted: false,
            },
        };

        let mut out_buf = BytesMut::new();
        let hello_packet = Packet::control_hello(100_000, &hello).unwrap();
        hello_packet.encode(&mut out_buf);
        socket.write_all(&out_buf).await.unwrap();
        socket.flush().await.unwrap();

        // 2. Stream synthetic video frames and reply to pings
        let mut read_buf = BytesMut::with_capacity(1024);
        let dummy_frame_payload = vec![0xAA; 10_000];

        let mut frame_count = 0;
        while !server_shutdown_clone.load(Ordering::Relaxed) && frame_count < 30 {
            // Send synthetic video frame
            out_buf.clear();
            let frame_packet = Packet::video_frame(
                0,
                100_000 + frame_count * 16_666,
                bytes::Bytes::from(dummy_frame_payload.clone()),
            );
            frame_packet.encode(&mut out_buf);
            if socket.write_all(&out_buf).await.is_err() {
                break;
            }
            let _ = socket.flush().await;
            frame_count += 1;

            // Check if client sent any pings
            if let Ok(Ok(bytes_read)) =
                tokio::time::timeout(Duration::from_millis(15), socket.read_buf(&mut read_buf))
                    .await
                && bytes_read > 0
            {
                while let Ok(Some(pkt)) = Packet::decode(&mut read_buf) {
                    if pkt.header.packet_type == PacketType::HeartbeatPing {
                        out_buf.clear();
                        let pong = Packet::heartbeat_pong(pkt.header.pts_us);
                        pong.encode(&mut out_buf);
                        let _ = socket.write_all(&out_buf).await;
                        let _ = socket.flush().await;
                    }
                }
            }
        }
    });

    // Run client loop
    let config = ClientConfig {
        endpoint,
        interface: None,
        ping_interval: Duration::from_millis(50),
        read_buffer_capacity: 65536,
    };
    let (event_tx, mut event_rx) = mpsc::channel(64);
    let client_shutdown = Arc::new(AtomicBool::new(false));

    let client_shutdown_clone = client_shutdown.clone();
    let client_task = tokio::spawn(async move {
        let _ = run_client_loop(config, event_tx, client_shutdown_clone).await;
    });

    let mut received_hello = false;
    let mut received_frames = 0;

    let timeout_dur = Duration::from_secs(3);
    let start = std::time::Instant::now();

    while start.elapsed() < timeout_dur {
        if let Ok(Some(event)) =
            tokio::time::timeout(Duration::from_millis(200), event_rx.recv()).await
        {
            match event {
                ClientEvent::Hello(hello) => {
                    assert_eq!(hello.device_name, "Mock Pixel Phone");
                    assert_eq!(hello.cameras.len(), 1);
                    received_hello = true;
                }
                ClientEvent::VideoFrame { payload_len, .. } => {
                    assert_eq!(payload_len, 10_000);
                    received_frames += 1;
                    if received_frames >= 10 {
                        break;
                    }
                }
                _ => {}
            }
        }
    }

    server_shutdown.store(true, Ordering::Relaxed);
    client_shutdown.store(true, Ordering::Relaxed);

    let _ = server_handle.await;
    let _ = client_task.await;

    assert!(received_hello, "Client should receive CONTROL_HELLO");
    assert!(
        received_frames >= 10,
        "Client should receive at least 10 video frames"
    );
}
