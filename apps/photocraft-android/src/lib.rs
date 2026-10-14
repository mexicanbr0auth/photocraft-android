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
            // Compact panels leave more room for the canvas on landscape phones.
            cc.egui_ctx.set_zoom_factor(1.05);
            let repaint = cc.egui_ctx.clone();
            let mut services = photocraft_ui_egui::Services::default();
            services.import = Some(Box::new(|name, bytes, max_svg_group_depth| {
                photocraft_io::import_with_svg_group_depth(name, bytes, max_svg_group_depth)
                    .map(|result| (result.document, result.warnings))
                    .map_err(|error| error.to_string())
            }));
            let inbox: photocraft_ui_egui::Inbox = Default::default();
            services.inbox = Some(inbox.clone());
            let bridge = app_storage.join("dialog-bridge");
            let _ = std::fs::create_dir_all(&bridge);
            services.file_dialog = Some(Box::new(move |request, _, reply| {
                use photocraft_ui_egui::{FileDialogRequest, FileDialogAnswer};
                let request_path = bridge.join("request");
                let response_path = bridge.join("response");
                let _ = std::fs::remove_file(&response_path);
                let kind = match request {
                    FileDialogRequest::Open { multiple, .. } => if multiple { "open-multiple" } else { "open" },
                    FileDialogRequest::Save { .. } => "save",
                    FileDialogRequest::Folder { .. } => "folder",
                };
                if let Err(err) = std::fs::write(&request_path, kind) {
                    reply.send(Some(FileDialogAnswer::Unavailable(err.to_string())));
                    return;
                }
                let bridge = bridge.clone();
                std::thread::spawn(move || {
                    for _ in 0..600 {
                        if let Ok(answer) = std::fs::read_to_string(bridge.join("response")) {
                            let _ = std::fs::remove_file(bridge.join("response"));
                            let paths: Vec<String> = answer.lines().map(str::to_owned).filter(|s| !s.is_empty()).collect();
                            let result = if paths.is_empty() { None } else {
                                Some(match kind {
                                    "save" => FileDialogAnswer::SaveTo(paths[0].clone()),
                                    "folder" => FileDialogAnswer::FolderTo(paths[0].clone()),
                                    _ => FileDialogAnswer::Paths(paths),
                                })
                            };
                            reply.send(result);
                            return;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(150));
                    }
                    reply.send(Some(FileDialogAnswer::Unavailable("Android file picker timed out".into())));
                });
            }));
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
            let mut editor = editor;
            editor.ui.panels.color = false;
            editor.ui.panels.properties = false;
            editor.ui.panels.history = false;
            editor.ui.panels.navigator = false;
            editor.ui.panels.rail = true;
            photocraft_ui_egui::panels::request_dock_width(&cc.egui_ctx, 250.0);
            Ok(Box::new(editor))
        }),
    );
}
