//! Game process spawning and monitoring.

use std::process::Stdio;
use std::time::Duration;

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use super::hooks::LaunchCommand;
use crate::authentication::LaunchAuth;
use crate::database::now_ts;
use crate::database::repos::InstanceRow;
use crate::errors::{BloomError, BloomResult};
use crate::instances::INTERNAL_DIR;
use crate::models::{LogLine, Stage};
use crate::services::events;
use crate::services::state::AppState;

const KEEP_LOG_FILES: usize = 15;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LogBatch<'a> {
    instance_id: &'a str,
    lines: &'a [LogLine],
}

/// Replaces secrets in any text that leaves the process (logs, UI).
struct Redactor {
    secrets: Vec<String>,
}

impl Redactor {
    fn apply(&self, s: &str) -> String {
        let mut out = s.to_string();
        for secret in &self.secrets {
            if !secret.is_empty() && out.contains(secret.as_str()) {
                out = out.replace(secret.as_str(), "<redacted>");
            }
        }
        out
    }
}

/// Reassembles the log4j XML events that Mojang's logging configuration
/// prints to stdout into the familiar `[12:34:56] [thread/LEVEL]: message`
/// lines. Plain lines (e.g. from old versions or the JVM) pass through.
#[derive(Default)]
struct Log4jAssembler {
    event: Option<String>,
}

impl Log4jAssembler {
    fn push(&mut self, line: &str) -> Vec<String> {
        match self.event.as_mut() {
            Some(buf) => {
                buf.push('\n');
                buf.push_str(line);
                if line.contains("</log4j:Event>") {
                    let event = self.event.take().unwrap_or_default();
                    return format_event(&event);
                }
                Vec::new()
            }
            None if line.trim_start().starts_with("<log4j:Event") => {
                if line.contains("</log4j:Event>") {
                    return format_event(line);
                }
                self.event = Some(line.to_string());
                Vec::new()
            }
            None => vec![line.to_string()],
        }
    }

    /// Flushes a partially received event (process exited mid-event).
    fn finish(&mut self) -> Vec<String> {
        self.event.take().map(|e| e.lines().map(String::from).collect()).unwrap_or_default()
    }
}

fn xml_attr<'a>(tag: &'a str, name: &str) -> Option<&'a str> {
    let key = format!("{name}=\"");
    let start = tag.find(&key)? + key.len();
    let end = tag[start..].find('"')? + start;
    Some(&tag[start..end])
}

fn cdata<'a>(xml: &'a str, element: &str) -> Option<&'a str> {
    let open = format!("<log4j:{element}><![CDATA[");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find("]]>")? + start;
    Some(&xml[start..end])
}

fn unescape(s: &str) -> String {
    s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}

fn format_event(xml: &str) -> Vec<String> {
    let tag_end = xml.find('>').unwrap_or(xml.len());
    let tag = &xml[..tag_end];
    let level = xml_attr(tag, "level").unwrap_or("INFO");
    let thread = xml_attr(tag, "thread").map(unescape).unwrap_or_default();
    let time = xml_attr(tag, "timestamp")
        .and_then(|t| t.parse::<i64>().ok())
        .and_then(chrono::DateTime::from_timestamp_millis)
        .map(|d| d.with_timezone(&chrono::Local).format("%H:%M:%S").to_string())
        .unwrap_or_default();
    let message = cdata(xml, "Message").unwrap_or_default();
    let mut out: Vec<String> = Vec::new();
    for (i, l) in message.lines().enumerate() {
        out.push(if i == 0 { format!("[{time}] [{thread}/{level}]: {l}") } else { l.to_string() });
    }
    if out.is_empty() {
        out.push(format!("[{time}] [{thread}/{level}]:"));
    }
    if let Some(t) = cdata(xml, "Throwable") {
        out.extend(t.lines().map(String::from));
    }
    out
}

fn prune_logs(dir: &std::path::Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let mut logs: Vec<_> = entries
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with("launch-"))
        .collect();
    if logs.len() <= KEEP_LOG_FILES {
        return;
    }
    logs.sort_by_key(|e| e.file_name());
    for e in &logs[..logs.len() - KEEP_LOG_FILES] {
        let _ = std::fs::remove_file(e.path());
    }
}

fn set_window_visibility(app: &AppHandle, mode: &str, restore: bool) {
    let Some(w) = app.get_webview_window("main") else { return };
    if restore {
        match mode {
            "hide" => {
                let _ = w.show();
                let _ = w.set_focus();
            }
            "minimize" => {
                let _ = w.unminimize();
            }
            _ => {}
        }
    } else {
        match mode {
            "hide" => {
                let _ = w.hide();
            }
            "minimize" => {
                let _ = w.minimize();
            }
            _ => {}
        }
    }
}

fn notify(app: &AppHandle, title: &str, body: &str) {
    use tauri_plugin_notification::NotificationExt;
    let _ = app.notification().builder().title(title).body(body).show();
}

pub async fn spawn_and_monitor(
    app: &AppHandle,
    state: &AppState,
    command: LaunchCommand,
    instance: &InstanceRow,
    auth: &LaunchAuth,
    cancel: CancellationToken,
) -> BloomResult<()> {
    let settings = state.settings();
    let redactor = Redactor { secrets: vec![auth.access_token.clone()] };
    let log_dir = command.game_dir.join(INTERNAL_DIR).join("logs");
    tokio::fs::create_dir_all(&log_dir).await?;
    prune_logs(&log_dir);
    let log_path = log_dir.join(format!("launch-{}.log", chrono::Local::now().format("%Y%m%d-%H%M%S")));
    let mut log_file = tokio::io::BufWriter::new(tokio::fs::File::create(&log_path).await?);

    let argv = command.argv();
    let printable = redactor.apply(&format!("{} {}", command.java.display(), argv.join(" ")));
    tracing::info!(instance = command.instance_id, version = command.version_id, "launching: {printable}");
    log_file
        .write_all(format!("[Bloom] Java {} - {}\n[Bloom] Command: {printable}\n", command.java_major, command.java.display()).as_bytes())
        .await?;

    let mut cmd = tokio::process::Command::new(&command.java);
    cmd.args(&argv)
        .current_dir(&command.game_dir)
        .envs(&command.env)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(false);
    #[cfg(windows)]
    cmd.creation_flags(crate::java::CREATE_NO_WINDOW);

    let mut child = cmd
        .spawn()
        .map_err(|e| BloomError::Launch(format!("could not start Java ({}): {e}", command.java.display())))?;
    let pid = child.id();
    let started_at = now_ts();
    let history_id = state.db.insert_launch(
        &command.instance_id,
        &command.version_id,
        Some(&auth.uuid),
        Some(&auth.username),
        started_at,
        Some(&log_path.to_string_lossy()),
    )?;
    state.sessions.clear_logs(&command.instance_id);
    state.sessions.set_running(app, &command.instance_id, pid, started_at);
    set_window_visibility(app, &settings.launcher_visibility_on_launch, false);

    // Output pump: two readers feed a channel, one batcher flushes to the
    // UI, the session buffer and the log file at most every 100 ms.
    let (tx, mut rx) = mpsc::channel::<LogLine>(8192);
    let redactor = std::sync::Arc::new(redactor);
    let mut readers = Vec::new();
    if let Some(out) = child.stdout.take() {
        readers.push(spawn_reader(out, "stdout", tx.clone(), redactor.clone()));
    }
    if let Some(err) = child.stderr.take() {
        readers.push(spawn_reader(err, "stderr", tx.clone(), redactor.clone()));
    }
    drop(tx);

    let batch_app = app.clone();
    let batch_id = command.instance_id.clone();
    let batcher = tokio::spawn(async move {
        let mut buffer: Vec<LogLine> = Vec::new();
        let mut ticker = tokio::time::interval(Duration::from_millis(100));
        loop {
            tokio::select! {
                line = rx.recv() => match line {
                    Some(l) => buffer.push(l),
                    None => break,
                },
                _ = ticker.tick() => {
                    flush(&batch_app, &batch_id, &mut buffer, &mut log_file).await;
                }
            }
        }
        flush(&batch_app, &batch_id, &mut buffer, &mut log_file).await;
        let _ = log_file.flush().await;
    });

    let mut killed = false;
    let status = tokio::select! {
        s = child.wait() => s,
        _ = cancel.cancelled() => {
            killed = true;
            let _ = child.kill().await;
            child.wait().await
        }
    };
    for r in readers {
        let _ = r.await;
    }
    let _ = batcher.await;

    let exit_code = status.as_ref().ok().and_then(|s| s.code());
    let ended_at = now_ts();
    let crashed = !killed && exit_code != Some(0);
    state.db.finish_launch(history_id, ended_at, exit_code, crashed)?;
    state.db.record_instance_played(&command.instance_id, started_at, ended_at - started_at)?;

    let (stage, message) = if killed {
        (Stage::Exited, "Game stopped".to_string())
    } else if crashed {
        (
            Stage::Crashed,
            match exit_code {
                Some(c) => format!("Minecraft exited unexpectedly (code {c})"),
                None => "Minecraft was terminated unexpectedly".to_string(),
            },
        )
    } else {
        (Stage::Exited, "Game closed".to_string())
    };
    state.sessions.finish(app, &command.instance_id, stage, message.clone(), exit_code);
    set_window_visibility(app, &settings.launcher_visibility_on_launch, true);
    let _ = app.emit(events::INSTANCES_CHANGED, ());
    if crashed && settings.notifications {
        notify(app, &format!("{} crashed", instance.name), &message);
    }
    Ok(())
}

fn spawn_reader<R: tokio::io::AsyncRead + Unpin + Send + 'static>(
    stream: R,
    name: &'static str,
    tx: mpsc::Sender<LogLine>,
    redactor: std::sync::Arc<Redactor>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut reader = BufReader::new(stream);
        let mut buf = Vec::with_capacity(512);
        let mut assembler = Log4jAssembler::default();
        loop {
            buf.clear();
            let lines = match reader.read_until(b'\n', &mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    let text = String::from_utf8_lossy(&buf);
                    assembler.push(text.trim_end_matches(['\r', '\n']))
                }
            };
            for line in lines {
                if tx.send(LogLine { stream: name, line: redactor.apply(&line) }).await.is_err() {
                    return;
                }
            }
        }
        for line in assembler.finish() {
            let _ = tx.send(LogLine { stream: name, line: redactor.apply(&line) }).await;
        }
    })
}

async fn flush(
    app: &AppHandle,
    instance_id: &str,
    buffer: &mut Vec<LogLine>,
    file: &mut tokio::io::BufWriter<tokio::fs::File>,
) {
    if buffer.is_empty() {
        return;
    }
    if let Some(state) = app.try_state::<AppState>() {
        state.sessions.push_logs(instance_id, buffer);
    }
    let _ = app.emit(events::GAME_LOG, LogBatch { instance_id, lines: buffer });
    for l in buffer.iter() {
        let _ = file.write_all(l.line.as_bytes()).await;
        let _ = file.write_all(b"\n").await;
    }
    buffer.clear();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log4j_events_are_reassembled() {
        let mut a = Log4jAssembler::default();
        assert!(a
            .push(r#"  <log4j:Event logger="x" timestamp="1793356794787" level="WARN" thread="Render thread">"#)
            .is_empty());
        assert!(a.push("    <log4j:Message><![CDATA[Hello world").is_empty());
        assert!(a.push("second line]]></log4j:Message>").is_empty());
        let out = a.push("  </log4j:Event>");
        assert_eq!(out.len(), 2);
        assert!(out[0].ends_with("[Render thread/WARN]: Hello world"), "{}", out[0]);
        assert_eq!(out[1], "second line");
        assert_eq!(a.push("plain JVM output"), vec!["plain JVM output".to_string()]);
    }

    #[test]
    fn log4j_throwables_are_kept() {
        let mut a = Log4jAssembler::default();
        a.push(r#"<log4j:Event logger="x" timestamp="0" level="ERROR" thread="main">"#);
        a.push("<log4j:Message><![CDATA[Boom]]></log4j:Message>");
        a.push("<log4j:Throwable><![CDATA[java.lang.RuntimeException: x");
        a.push("\tat a.b(C.java:1)]]></log4j:Throwable>");
        let out = a.push("</log4j:Event>");
        assert!(out[0].contains("[main/ERROR]: Boom"));
        assert_eq!(out[1], "java.lang.RuntimeException: x");
        assert_eq!(out[2], "\tat a.b(C.java:1)");
    }

    #[test]
    fn redactor_hides_tokens() {
        let r = Redactor { secrets: vec!["secret-token".into()] };
        assert_eq!(r.apply("--accessToken secret-token x"), "--accessToken <redacted> x");
    }
}
