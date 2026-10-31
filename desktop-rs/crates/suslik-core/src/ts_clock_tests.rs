//! Перенос `TsClockTest.kt`.

use super::*;

/// Тесты, что трогают общий кеш часов, идут по одному.
static SERIAL: Mutex<()> = Mutex::new(());

fn serial() -> MutexGuard<'static, ()> {
    SERIAL.lock().unwrap_or_else(|e| e.into_inner())
}

/// Один пакет TS; с PCR (90 кГц `base`) в поле адаптации, когда `base` ≥ 0.
fn packet(pid: i32, base: i64) -> Vec<u8> {
    let mut b = vec![0xFFu8; 188];
    b[0] = 0x47;
    b[1] = ((pid >> 8) & 0x1F) as u8;
    b[2] = (pid & 0xFF) as u8;
    if base < 0 {
        b[3] = 0x10; // только данные
        return b;
    }
    b[3] = 0x30;
    b[4] = 7;
    b[5] = 0x10;
    b[6] = (base >> 25) as u8;
    b[7] = (base >> 17) as u8;
    b[8] = (base >> 9) as u8;
    b[9] = (base >> 1) as u8;
    b[10] = (((base & 1) << 7) | 0x7E) as u8;
    b[11] = 0;
    b
}

/// Поток: пакеты с PCR через несколько пакетов; M2TS добавляет к каждому 4 байта заголовка.
fn stream(pcrs: &[i64], m2ts: bool, junk_at_start: usize) -> Vec<u8> {
    let mut out = vec![0x12u8; junk_at_start]; // кусок из середины файла начинается где угодно
    for &pcr in pcrs {
        for pk in [packet(0x1011, -1), packet(0x1100, -1), packet(0x1001, pcr), packet(0x1011, -1)] {
            if m2ts {
                out.extend_from_slice(&[0, 0, 0, 0]);
            }
            out.extend_from_slice(&pk);
        }
    }
    out
}

fn close(a: f32, b: f32, eps: f32) {
    assert!((a - b).abs() <= eps, "{a} != {b}");
}

#[test]
fn duration_of_a_bluray_stream() {
    let start = 90_000i64 * 600; // часы диска начинаются не с 0
    let head = stream(&(0..=20).map(|i| start + i * 9_000).collect::<Vec<_>>(), true, 0);
    let end = start + 90_000 * 8_000; // через 2 ч 13 мин 20 с
    let tail = stream(&(0..=20).map(|i| end - (20 - i) * 9_000).collect::<Vec<_>>(), true, 77);
    assert_eq!(duration_ms(&head, &tail), Some(8_000_000));
}

#[test]
fn plain_ts_and_a_clock_that_wraps() {
    let wrap = 1i64 << 33;
    let head = stream(&[wrap - 90_000 * 10, wrap - 90_000 * 9], false, 0);
    let tail = stream(&[90_000 * 50, 90_000 * 51], false, 5);
    assert_eq!(duration_ms(&head, &tail), Some(61_000)); // 10 с до переполнения + 51 с после
}

#[test]
fn no_clock_no_duration() {
    assert_eq!(duration_ms(&[0u8; 4096], &[0u8; 4096]), None);
    assert!(pcrs(&[0u8; 10]).is_empty());
}

const HOUR: i64 = 3_600_000;

#[test]
fn resume_jumps_at_once_and_adds_the_offset_when_the_player_counts_from_zero() {
    let ts = TsPlayback::new(2 * HOUR, "");
    let pos = ts.open_jump(HOUR).unwrap();
    close(pos, 0.5, 0.0001);
    assert_eq!(ts.open_landed(800, 0.0003), None); // ещё начало файла
    assert_eq!(ts.open_landed(2_000, 0.5002), Some(HOUR)); // там, и плеер считает с 0: приземлились на сохранённом
    assert_eq!(ts.film_time(60_000), HOUR + 58_000); // ...и дальше время следует за ним
    assert!(!ts.opening());
}

#[test]
fn resume_where_the_player_keeps_the_real_clock_needs_no_offset() {
    let ts = TsPlayback::new(2 * HOUR, "");
    ts.open_jump(HOUR);
    assert_eq!(ts.open_landed(HOUR + 1_000, 0.5), Some(HOUR + 1_000));
    assert_eq!(ts.offset(), 0);
    assert_eq!(TsPlayback::new(2 * HOUR, "").open_jump(5_000), None); // несколько секунд: просто с начала
}

#[test]
fn seeks_are_measured_from_here_and_from_a_jump_in_flight() {
    let ts = TsPlayback::new(2 * HOUR, "");
    // плеер на 30 мин, а его доля файла 0,26 (битрейт неровный): +10 с считаются оттуда
    let p1 = ts.seek(30 * 60_000 + 10_000, 30 * 60_000, 0.26);
    close(p1, 0.26 + 10_000.0 / (2 * HOUR) as f32, 0.00001);
    // следующее нажатие, пока плеер не дошёл: он ещё говорит 30:00, а шаг считается от первой цели
    let p2 = ts.seek(30 * 60_000 + 20_000, 30 * 60_000, p1);
    close(p2, p1 + 10_000.0 / (2 * HOUR) as f32, 0.00001);
    assert!(!ts.seek_landed(30 * 60_000 + 300, 0.0)); // всё ещё старое место
    assert!(ts.seek_landed(30 * 60_000 + 19_000, 0.0)); // приземлились
    assert!(!ts.seeking());
}

#[test]
fn recognises_bluray_files() {
    assert!(is_ts("https://x/api/file/proxy/00800.m2ts?link=a&sig=b"));
    assert!(!is_ts("https://x/api/file/proxy/Film.mkv?link=a"));
}

#[test]
fn piecewise_interpolation_corrects_vbr_skew() {
    let _s = serial();
    let url = "https://x/api/file/proxy/test.m2ts?link=testvbr";
    clear_cache();
    let total = 5_000_000; // 5000 с
                           // без точек: линейно
    close(pos_for(url, 2_500_000, total), 0.5, 0.0001);
    // первая половина с большим битрейтом: 2500 с — это 0,60 файла
    let info = Arc::new(ClockInfo::new(total, 10_000_000_000, 0x1011, 0));
    {
        let mut pts = info.checkpoints();
        pts.insert(0, 0.0);
        pts.insert(2_500_000, 0.60);
        pts.insert(total, 1.0);
    }
    put_clock_info(url, info);
    close(pos_for(url, 2_500_000, total), 0.60, 0.0001);
    learn(url, 2_500_000, 0.60);
    close(pos_for(url, 1_250_000, total), 0.30, 0.0001);
}

#[test]
fn pos_for_interpolates_accurately() {
    let _s = serial();
    let url = "https://x/api/file/proxy/interp.m2ts?link=interp";
    clear_cache();
    let info = Arc::new(ClockInfo::new(5_000_000, 10_000_000_000, 0x1011, 0));
    {
        let mut pts = info.checkpoints();
        pts.insert(0, 0.0);
        pts.insert(2_000_000, 0.50); // 2000 с = 0,50
        pts.insert(5_000_000, 1.0); // 5000 с = 1,00
    }
    put_clock_info(url, info);
    // 1000 с: посередине между 0 (0,0) и 2000 с (0,50) → 0,25 (а не 1000/5000 = 0,20)
    close(pos_for(url, 1_000_000, 5_000_000), 0.25, 0.0001);
    // 3500 с: посередине между 2000 с (0,50) и 5000 с (1,00) → 0,75 (а не 0,70)
    close(pos_for(url, 3_500_000, 5_000_000), 0.75, 0.0001);
    // узнали новую точку: 3000 с = 0,60
    learn(url, 3_000_000, 0.60);
    // 2500 с: между 2000 с (0,50) и 3000 с (0,60) → 0,55
    close(pos_for(url, 2_500_000, 5_000_000), 0.55, 0.0001);
}

#[test]
fn a_learned_point_removes_the_ones_that_contradict_it() {
    let _s = serial();
    let url = "https://x/api/file/proxy/mono.m2ts?link=mono";
    clear_cache();
    let info = Arc::new(ClockInfo::new(5_000_000, 10_000_000_000, 0x1011, 0));
    {
        let mut pts = info.checkpoints();
        pts.insert(0, 0.0);
        pts.insert(2_000_000, 0.50); // плохая проба: слишком далеко в файле
        pts.insert(5_000_000, 1.0);
    }
    put_clock_info(url, info.clone());
    learn(url, 3_000_000, 0.40); // плеер: 3000 с — это 0,40, значит 2000 с не может быть на 0,50
    assert_eq!(info.checkpoints().keys().copied().collect::<Vec<_>>(), vec![0, 3_000_000, 5_000_000]);
    // доля растёт со временем везде
    let mut prev = -1.0f32;
    for t in (0..=5_000_000).step_by(250_000) {
        let p = pos_for(url, t, 5_000_000);
        assert!(p >= prev, "at {t}: {p} < {prev}");
        prev = p;
    }
}
