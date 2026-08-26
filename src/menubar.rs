//! macOS menu-bar surface (T02).
//!
//! A mic-shaped status item lives in the menu bar; clicking it opens a popover
//! showing the pairing QR, URL, PIN, and connection state, plus two actions:
//! regenerate the PIN and Quit.
//!
//! Design notes:
//! - Typed Cocoa bindings (`objc2` / `objc2-app-kit`) instead of a full app
//!   framework keep the dependency tree small. This module only compiles on
//!   macOS (`main.rs` cfg-gates it); Windows/Linux binaries are unaffected.
//! - AppKit requires the main thread, so `main.rs` hands the main thread over to
//!   [`run`] while the servers run on tokio worker threads.
//! - Quit routes through `quit_tx` into exactly the same graceful-shutdown path
//!   Ctrl+C takes (`main::perform_shutdown`) — there is deliberately no second
//!   shutdown implementation.
//! - The status-item glyph is drawn programmatically from the geometry of the
//!   vendored Tabler microphone outline (`web/vendor/tabler/microphone.svg`,
//!   MIT © Paweł Kuna): capsule + pickup bowl + stem + base, stroke width 2 in
//!   the 24-unit source space. Drawing at runtime gives a crisp template image
//!   at any backing scale.
//! - The pairing QR encodes the exact same string the terminal prints
//!   (`url#pin`); rendered as a native image via `qrcodegen`.

use std::cell::Cell;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use objc2::rc::Retained;
use objc2::runtime::{AnyObject, Bool, NSObject, NSObjectProtocol};
use objc2::{define_class, msg_send, sel, DefinedClass, MainThreadOnly};
use objc2_app_kit::{
    NSApplication, NSApplicationActivationPolicy, NSBezelStyle, NSBezierPath, NSButton, NSColor,
    NSFont, NSImage, NSImageView, NSLineCapStyle, NSLineJoinStyle, NSPopover, NSPopoverBehavior,
    NSStatusBar, NSStatusItem, NSTextAlignment, NSTextField, NSView, NSViewController,
};
use objc2_foundation::{
    ns_string, MainThreadMarker, NSPoint, NSRect, NSRectEdge, NSSize, NSString, NSTimer,
};
use qrcodegen::{QrCode, QrCodeEcc};

/// How often the popover's connection label refreshes. This poll also notices a
/// fatal server-side completion (crashed server task) and brings the UI down so
/// `macos_entry` can surface the error.
const TICK_SECONDS: f64 = 0.5;

/// Server-side completion slot shared with `main::macos_entry`: the bridge thread
/// fills it when `run_to_completion` returns (graceful shutdown finished, or a
/// fatal server error surfaced).
#[derive(Default)]
pub struct ServerCompletion {
    pub finished: AtomicBool,
    pub error: parking_lot::Mutex<Option<String>>,
}

/// Everything the menubar surface needs from the server half of the process.
pub struct MenubarConfig {
    /// Base URL (no PIN fragment), identical to the terminal banner's.
    pub url: String,
    /// Pairing URL including the current PIN fragment (`url#pin`). Regenerated
    /// whenever the PIN rotates. Must stay byte-compatible with what the
    /// terminal QR prints — phones scan this exact string.
    pub qr_url: String,
    /// Live pairing PIN (shared with `AppState.pairing_pin`).
    pub pin: Arc<parking_lot::Mutex<String>>,
    pub is_connected: Arc<AtomicBool>,
    /// False while the audio output device is lost and being rebuilt.
    pub audio_device_ok: Arc<AtomicBool>,
    /// Fired by the Quit action; consumed by `main::run_to_completion`, which
    /// performs the standard graceful shutdown.
    pub quit_tx: Arc<tokio::sync::watch::Sender<bool>>,
    pub completion: Arc<ServerCompletion>,
}

struct CoordinatorIvars {
    mtm: MainThreadMarker,
    config: MenubarConfig,
    popover: Retained<NSPopover>,
    status_item: Retained<NSStatusItem>,
    qr_image_view: Retained<NSImageView>,
    pin_label: Retained<NSTextField>,
    status_label: Retained<NSTextField>,
    /// Whether the event loop ended because the user chose Quit (vs. a fatal
    /// server error stopping the app).
    user_quit: Cell<bool>,
}

define_class!(
    // SAFETY:
    // - `NSObject` has no subclassing requirements.
    // - `Coordinator` does not implement `Drop`.
    #[unsafe(super = NSObject)]
    #[thread_kind = MainThreadOnly]
    #[ivars = CoordinatorIvars]
    struct Coordinator;

    // SAFETY: `NSObjectProtocol` has no safety requirements.
    unsafe impl NSObjectProtocol for Coordinator {}

    impl Coordinator {
        /// Status-item click: toggle the popover (refreshing its contents first).
        #[unsafe(method(togglePopover:))]
        unsafe fn toggle_popover(&self, _sender: Option<&AnyObject>) {
            let ivars = self.ivars();
            if ivars.popover.isShown() {
                ivars.popover.close();
                return;
            }
            self.refresh_labels();
            self.refresh_qr();
            let button = ivars.status_item.button(ivars.mtm).expect("status item button");
            ivars.popover.showRelativeToRect_ofView_preferredEdge(
                button.bounds(),
                &button,
                NSRectEdge::MinY,
            );
            // A `Transient` popover dismisses itself on an outside click only
            // while its app is active. This is an accessory (menu-bar-only)
            // app, so it is NOT activated by clicking the status item, and the
            // popover stayed open until the item was clicked again. Activating
            // here restores the ordinary click-outside-to-close behavior.
            //
            // `activate()` is the modern replacement but only exists on macOS
            // 14+; calling a missing selector would crash on older systems, so
            // the long-standing call is kept deliberately.
            #[allow(deprecated)]
            NSApplication::sharedApplication(ivars.mtm).activateIgnoringOtherApps(true);
        }

        /// "New PIN": generate a fresh 6-digit number, publish it to the HTTP API
        /// (shared lock), persist it (best-effort), and redraw the QR.
        #[unsafe(method(newPin:))]
        unsafe fn new_pin(&self, _sender: Option<&AnyObject>) {
            let ivars = self.ivars();
            let new_pin = crate::generate_pin();

            // 1. The live value used by /api/pair.
            *ivars.config.pin.lock() = new_pin.clone();
            // 2. The persisted value that must survive restarts (best-effort, like
            //    every disk write in this project).
            crate::persist::update_pin(&new_pin);
            // 3. The UI.
            ivars
                .pin_label
                .setStringValue(&NSString::from_str(&new_pin));
            tracing::info!("Pairing PIN regenerated from the menu bar");
            self.refresh_qr();
        }

        /// "Quit": fire the shutdown signal (the same path Ctrl+C takes) and end
        /// the AppKit event loop.
        #[unsafe(method(quit:))]
        unsafe fn quit(&self, _sender: Option<&AnyObject>) {
            let ivars = self.ivars();
            ivars.user_quit.set(true);
            let _ = ivars.config.quit_tx.send(true);
            ivars.popover.close();
            NSApplication::sharedApplication(ivars.mtm).stop(None);
        }

        /// Periodic tick: refresh the connection label and notice a fatal
        /// server-side completion so the error can surface on the main thread.
        #[unsafe(method(tick:))]
        unsafe fn tick(&self, _timer: Option<&AnyObject>) {
            let ivars = self.ivars();
            if ivars.config.completion.finished.load(Ordering::SeqCst) {
                ivars.user_quit.set(false);
                ivars.popover.close();
                NSApplication::sharedApplication(ivars.mtm).stop(None);
                return;
            }
            self.refresh_labels();
        }
    }
);

impl Coordinator {
    fn new(mtm: MainThreadMarker, config: MenubarConfig) -> Retained<Self> {
        let popover = NSPopover::new(mtm);
        popover.setBehavior(NSPopoverBehavior::Transient);

        let status_item = NSStatusBar::systemStatusBar().statusItemWithLength(-1.0);

        // ── Popover content (fixed frames, bottom-left origin coordinates) ──
        let container = NSView::initWithFrame(
            NSView::alloc(mtm),
            NSRect::new(NSPoint::new(0.0, 0.0), NSSize::new(260.0, 340.0)),
        );

        let qr_image_view = NSImageView::imageViewWithImage(
            &qr_image(&config.qr_url, mtm).expect("pairing URL fits in a QR code"),
            mtm,
        );
        qr_image_view.setFrame(NSRect::new(
            NSPoint::new(40.0, 135.0),
            NSSize::new(180.0, 180.0),
        ));
        container.addSubview(&qr_image_view);

        let url_label = make_label(&config.url, 12.0, false, true, mtm);
        url_label.setFrame(NSRect::new(
            NSPoint::new(10.0, 104.0),
            NSSize::new(240.0, 20.0),
        ));
        container.addSubview(&url_label);

        let pin_label = make_label(&config.pin.lock().clone(), 22.0, true, false, mtm);
        pin_label.setFrame(NSRect::new(
            NSPoint::new(10.0, 66.0),
            NSSize::new(240.0, 30.0),
        ));
        container.addSubview(&pin_label);

        let status_label = make_label("", 12.0, false, false, mtm);
        status_label.setFrame(NSRect::new(
            NSPoint::new(10.0, 42.0),
            NSSize::new(240.0, 18.0),
        ));
        container.addSubview(&status_label);

        // Target for both buttons (and the timer): an `&AnyObject` view of the
        // coordinator, kept alive for the process lifetime alongside everything
        // else here (this is a singleton UI that lives until exit).
        let coordinator = Self::alloc(mtm).set_ivars(CoordinatorIvars {
            mtm,
            config,
            popover,
            status_item,
            qr_image_view,
            pin_label,
            status_label,
            user_quit: Cell::new(false),
        });
        // SAFETY: `init` on NSObject is the designated initializer and the ivars
        // were just installed.
        let coordinator: Retained<Self> = unsafe { msg_send![super(coordinator), init] };
        let target: Retained<AnyObject> =
            unsafe { Retained::cast_unchecked::<AnyObject>(coordinator.clone()) };
        // Leak one strong reference so the target is never dangling while the
        // controls outlive this scope (process-lifetime UI singleton).
        std::mem::forget(target.clone());

        let new_pin_button = unsafe {
            NSButton::buttonWithTitle_target_action(
                ns_string!("New PIN"),
                Some(&target),
                Some(sel!(newPin:)),
                mtm,
            )
        };
        new_pin_button.setBezelStyle(NSBezelStyle::Push);
        new_pin_button.setFrame(NSRect::new(
            NSPoint::new(16.0, 8.0),
            NSSize::new(110.0, 30.0),
        ));
        container.addSubview(&new_pin_button);

        let quit_button = unsafe {
            NSButton::buttonWithTitle_target_action(
                ns_string!("Quit"),
                Some(&target),
                Some(sel!(quit:)),
                mtm,
            )
        };
        quit_button.setBezelStyle(NSBezelStyle::Push);
        quit_button.setFrame(NSRect::new(
            NSPoint::new(134.0, 8.0),
            NSSize::new(110.0, 30.0),
        ));
        container.addSubview(&quit_button);

        let view_controller = NSViewController::new(mtm);
        view_controller.setView(&container);
        coordinator
            .ivars()
            .popover
            .setContentViewController(Some(&view_controller));

        // ── Status item ──────────────────────────────────────────────────────
        let button = coordinator
            .ivars()
            .status_item
            .button(mtm)
            .expect("status items always have a button");
        button.setImage(Some(&microphone_icon()));
        // SAFETY: valid selector + correct target type.
        unsafe {
            button.setAction(Some(sel!(togglePopover:)));
            button.setTarget(Some(&target));
        }

        // Status/fatal-error poll on the run loop.
        let timer = unsafe {
            NSTimer::scheduledTimerWithTimeInterval_target_selector_userInfo_repeats(
                TICK_SECONDS,
                &target,
                sel!(tick:),
                None,
                true,
            )
        };
        std::mem::forget(timer);

        coordinator
    }

    fn refresh_labels(&self) {
        let ivars = self.ivars();
        let connected = ivars.config.is_connected.load(Ordering::SeqCst);
        let device_ok = ivars.config.audio_device_ok.load(Ordering::SeqCst);
        let (text, color) = if !device_ok {
            (
                "Audio output unavailable — rebuilding…",
                NSColor::systemOrangeColor(),
            )
        } else if connected {
            ("● Connected", NSColor::systemGreenColor())
        } else {
            ("○ Waiting for a device…", NSColor::secondaryLabelColor())
        };
        ivars.status_label.setStringValue(&NSString::from_str(text));
        ivars.status_label.setTextColor(Some(&color));
    }

    fn refresh_qr(&self) {
        let ivars = self.ivars();
        if let Some(image) = qr_image(&current_qr_url(ivars), ivars.mtm) {
            ivars.qr_image_view.setImage(Some(&image));
        }
    }
}

/// Build the current `url#pin` string from live state. Format-critical: phones
/// scan exactly this string (see the vendored QR contract in AGENTS.md).
fn current_qr_url(ivars: &CoordinatorIvars) -> String {
    format!("{}#{}", ivars.config.url, ivars.config.pin.lock().clone())
}

fn make_label(
    text: &str,
    size: f64,
    bold: bool,
    selectable: bool,
    mtm: MainThreadMarker,
) -> Retained<NSTextField> {
    let label = NSTextField::labelWithString(&NSString::from_str(text), mtm);
    let font = if bold {
        NSFont::boldSystemFontOfSize(size)
    } else {
        NSFont::systemFontOfSize(size)
    };
    label.setFont(Some(&font));
    label.setAlignment(NSTextAlignment::Center);
    label.setSelectable(selectable);
    if selectable {
        label.setTextColor(Some(&NSColor::secondaryLabelColor()));
    }
    label
}

/// Render `text` as a QR code image sized for the popover. White background,
/// black modules, standard 4-module quiet zone, ~6pt modules.
fn qr_image(text: &str, _mtm: MainThreadMarker) -> Option<Retained<NSImage>> {
    let code = QrCode::encode_text(text, QrCodeEcc::Medium).ok()?;
    const QUIET_ZONE_MODULES: f64 = 4.0;
    const POINTS_PER_MODULE: f64 = 6.0;

    let modules = f64::from(code.size());
    let dim = (modules + QUIET_ZONE_MODULES * 2.0) * POINTS_PER_MODULE;

    let handler = block2::RcBlock::new(move |_rect: NSRect| -> Bool {
        // White card behind the code so scanners always see full contrast.
        NSColor::whiteColor().setFill();
        NSBezierPath::bezierPathWithRect(NSRect::new(
            NSPoint::new(0.0, 0.0),
            NSSize::new(dim, dim),
        ))
        .fill();

        let path = NSBezierPath::bezierPath();
        for y in 0..code.size() {
            for x in 0..code.size() {
                if !code.get_module(x, y) {
                    continue;
                }
                path.appendBezierPath(&NSBezierPath::bezierPathWithRect(NSRect::new(
                    NSPoint::new(
                        (f64::from(x) + QUIET_ZONE_MODULES) * POINTS_PER_MODULE,
                        (f64::from(y) + QUIET_ZONE_MODULES) * POINTS_PER_MODULE,
                    ),
                    NSSize::new(POINTS_PER_MODULE, POINTS_PER_MODULE),
                )));
            }
        }
        NSColor::blackColor().setFill();
        path.fill();
        Bool::YES
    });

    let image = NSImage::imageWithSize_flipped_drawingHandler(
        NSSize::new(dim, dim),
        // Flipped context: y grows downward like the QR matrix (and like the
        // SVG coordinate space the mic icon is drawn in).
        true,
        &handler,
    );
    image.setSize(NSSize::new(dim, dim));
    Some(image)
}

/// Draw the Tabler microphone outline (`web/vendor/tabler/microphone.svg`) as an
/// 18pt template image for the status item. Geometry is transcribed 1:1 from the
/// SVG's four paths in its native y-down coordinate space (drawn flipped):
/// capsule (rounded rect), pickup bowl (semicircle), stem, base.
fn microphone_icon() -> Retained<NSImage> {
    const SIZE: f64 = 18.0;
    // SVG user units are 24x24; scale uniformly into SIZE points.
    let s = SIZE / 24.0;
    let p = move |v: f64| v * s;

    let handler = block2::RcBlock::new(move |_rect: NSRect| -> Bool {
        let path = NSBezierPath::bezierPath();
        path.setLineWidth(2.0 * s);
        path.setLineCapStyle(NSLineCapStyle::Round);
        path.setLineJoinStyle(NSLineJoinStyle::Round);

        // Capsule: SVG rounded rect x=9 y=2 w=6 h=12 r=3.
        path.appendBezierPath(&NSBezierPath::bezierPathWithRoundedRect_xRadius_yRadius(
            NSRect::new(NSPoint::new(p(9.0), p(2.0)), NSSize::new(p(6.0), p(12.0))),
            p(3.0),
            p(3.0),
        ));

        // Bowl: SVG `M5 10a7 7 0 0 0 14 0` — lower semicircle of the circle
        // centered at (12,10) r=7. Expressed as two cubic curves using the
        // standard kappa circle approximation (deterministic in a flipped
        // context, where arc-angle direction semantics get confusing).
        let k = 7.0f64 * 0.552_284_749_830_793_6;
        path.moveToPoint(NSPoint::new(p(5.0), p(10.0)));
        path.curveToPoint_controlPoint1_controlPoint2(
            NSPoint::new(p(12.0), p(17.0)),
            NSPoint::new(p(5.0), p(10.0 + k)),
            NSPoint::new(p(12.0 - k), p(17.0)),
        );
        path.curveToPoint_controlPoint1_controlPoint2(
            NSPoint::new(p(19.0), p(10.0)),
            NSPoint::new(p(12.0 + k), p(17.0)),
            NSPoint::new(p(19.0), p(10.0 + k)),
        );

        // Stem: SVG `M12 17l0 4`.
        path.moveToPoint(NSPoint::new(p(12.0), p(17.0)));
        path.lineToPoint(NSPoint::new(p(12.0), p(21.0)));

        // Base: SVG `M8 21l8 0`.
        path.moveToPoint(NSPoint::new(p(8.0), p(21.0)));
        path.lineToPoint(NSPoint::new(p(16.0), p(21.0)));

        NSColor::blackColor().setStroke();
        path.stroke();
        Bool::YES
    });

    let image =
        NSImage::imageWithSize_flipped_drawingHandler(NSSize::new(SIZE, SIZE), true, &handler);
    image.setTemplate(true);
    image
}

/// Run the menubar event loop on the current (main) thread. Blocks until the user
/// quits or a fatal server error stops the app. Returns `true` when the user
/// chose Quit (shutdown already fired), `false` when a fatal error ended the loop.
pub fn run(config: MenubarConfig) -> bool {
    let mtm = MainThreadMarker::new().expect("menubar must be created on the main thread");

    // Accessory policy: no Dock icon, no regular app window — just the item in
    // the menu bar. Works without an app bundle (dev runs via `cargo run`).
    let app = NSApplication::sharedApplication(mtm);
    app.setActivationPolicy(NSApplicationActivationPolicy::Accessory);

    let coordinator = Coordinator::new(mtm, config);
    app.run();

    let user_quit = coordinator.ivars().user_quit.get();
    user_quit
}
