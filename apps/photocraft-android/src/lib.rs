//! Native Android entry point for PhotoCraft.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub fn android_main(app: android_activity::AndroidApp) {
    let app_storage = app.internal_data_path().expect("Android private storage");
    let options = eframe::NativeOptions {
        android_app: Some(app),
        renderer: eframe::Renderer::Wgpu,
        ..Default::default()
    };
    let _ = eframe::run_native(
        "PhotoCraft",
        options,
        Box::new(move |cc| {
            cc.egui_ctx.set_zoom_factor(1.15);
            let repaint = cc.egui_ctx.clone();
            let mut services = photocraft_ui_egui::Services::default();
            let inbox: photocraft_ui_egui::Inbox = Default::default();
            services.inbox = Some(inbox.clone());
            let incoming = app_storage.join("incoming");
            let _ = std::fs::create_dir_all(&incoming);
            std::thread::spawn(move || loop {
                if let Ok(entries) = std::fs::read_dir(&incoming) {
                    for entry in entries.flatten() {
                        let path = entry.path();
                        if !path.is_file() || path.extension().is_some_and(|ext| ext == "part") { continue; }
                        if let (Some(name), Ok(bytes)) = (path.file_name(), std::fs::read(&path)) {
                            inbox.lock().unwrap_or_else(|e| e.into_inner())
                                .push((name.to_string_lossy().into_owned(), bytes));
                            let _ = std::fs::remove_file(path);
                            repaint.request_repaint();
                        }
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(800));
            });
            let editor = photocraft_ui_egui::PhotocraftApp::new(
                photocraft_engine::Session::new(), services,
            );
            Ok(Box::new(editor))
        }),
    );
}
