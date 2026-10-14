//! Native Android entry point for PhotoCraft.
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub fn android_main(app: android_activity::AndroidApp) {
    let options = eframe::NativeOptions {
        android_app: Some(app),
        renderer: eframe::Renderer::Wgpu,
        ..Default::default()
    };
    let _ = eframe::run_native(
        "PhotoCraft",
        options,
        Box::new(|cc| {
            cc.egui_ctx.set_zoom_factor(1.15);
            let editor = photocraft_ui_egui::PhotocraftApp::new(
                photocraft_engine::Session::new(),
                photocraft_ui_egui::Services::default(),
            );
            Ok(Box::new(editor))
        }),
    );
}
