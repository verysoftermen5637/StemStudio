import math
import os
import struct
import time
import urllib.request
import wave
import numpy as np

MODEL_URL_FP32 = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s.onnx"
MODEL_URL_FP16 = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx"

MODEL_STEM_ORDER = ["drums", "bass", "other", "vocals", "guitar", "piano"]
TARGET_SR = 44100
SEGMENT_SAMPLES = 343980
N_FFT = 4096
HOP_LENGTH = 1024


def _emit(callback, percent: int, message: str) -> None:
    p = max(0, min(100, int(percent)))
    if callback is not None:
        callback.report(p, str(message))


def ensure_model(model_dir: str, progress_callback=None, use_fp16: bool = True) -> str:
    os.makedirs(model_dir, exist_ok=True)
    filename = "htdemucs_6s_fp16weights.onnx" if use_fp16 else "htdemucs_6s.onnx"
    url = MODEL_URL_FP16 if use_fp16 else MODEL_URL_FP32
    model_path = os.path.join(model_dir, filename)
    temp_path = model_path + ".part"

    if os.path.isfile(model_path) and os.path.getsize(model_path) > 50 * 1024 * 1024:
        size_mb = os.path.getsize(model_path) / (1024 * 1024)
        _emit(progress_callback, 10, f"[MODEL] Найдена локальная модель: {filename} ({size_mb:.1f} MB) — режим 100% OFFLINE")
        return model_path

    _emit(progress_callback, 1, f"[NET] Модель не найдена. Скачивание {filename} с HuggingFace...")
    _emit(progress_callback, 2, f"[NET] URL: {url}")

    req = urllib.request.Request(
        url,
        headers={"User-Agent": "StemStudio-Android/1.0"}
    )

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            total_bytes = int(resp.headers.get("Content-Length", 0))
            downloaded = 0
            chunk_size = 256 * 1024
            last_report_pct = -1
            t0 = time.time()

            with open(temp_path, "wb") as out_f:
                while True:
                    buf = resp.read(chunk_size)
                    if not buf:
                        break
                    out_f.write(buf)
                    downloaded += len(buf)

                    if total_bytes > 0:
                        dl_pct = int((downloaded / total_bytes) * 100)
                        if dl_pct >= last_report_pct + 5 or dl_pct == 100:
                            last_report_pct = dl_pct
                            elapsed = max(0.01, time.time() - t0)
                            speed_mb = (downloaded / (1024 * 1024)) / elapsed
                            done_mb = downloaded / (1024 * 1024)
                            tot_mb = total_bytes / (1024 * 1024)
                            overall = 2 + int(dl_pct * 0.18)
                            _emit(
                                progress_callback,
                                overall,
                                f"[NET] Загрузка модели: {done_mb:.1f}/{tot_mb:.1f} MB ({dl_pct}%) — {speed_mb:.2f} MB/s"
                            )

        os.replace(temp_path, model_path)
        _emit(progress_callback, 20, f"[MODEL] Модель сохранена: {model_path}")
        return model_path

    except Exception as exc:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass
        _emit(progress_callback, 0, f"[ERROR] Ошибка загрузки модели: {exc}")
        raise


def read_wav_stereo_float32(wav_path: str) -> tuple[np.ndarray, int]:
    with wave.open(wav_path, "rb") as wf:
        n_channels = wf.getnchannels()
        sampwidth = wf.getsampwidth()
        sr = wf.getframerate()
        n_frames = wf.getnframes()
        raw_bytes = wf.readframes(n_frames)

    if sampwidth != 2:
        raise ValueError(f"Ожидается 16-bit PCM WAV, получено {sampwidth * 8}-bit")

    samples = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0
    if n_channels == 1:
        audio = np.stack([samples, samples], axis=0)
    else:
        audio = samples.reshape(-1, n_channels)[:, :2].T.copy()

    return audio, sr


def write_wav_stereo_int16(wav_path: str, audio: np.ndarray, sr: int = TARGET_SR) -> None:
    audio = np.asarray(audio, dtype=np.float32)
    if audio.ndim == 1:
        audio = np.stack([audio, audio], axis=0)

    peak = float(np.max(np.abs(audio))) if audio.size > 0 else 0.0
    if peak > 0.999:
        audio = audio * (0.999 / peak)

    interleaved = np.clip(audio.T, -1.0, 1.0)
    pcm16 = (interleaved * 32767.0).astype("<i2")

    with wave.open(wav_path, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm16.tobytes())


def stft_numpy(audio_stereo: np.ndarray, n_fft: int = N_FFT, hop_length: int = HOP_LENGTH) -> np.ndarray:
    pad = n_fft // 2
    padded = np.pad(audio_stereo, ((0, 0), (pad, pad)), mode="reflect")
    window = np.hanning(n_fft + 1)[:-1].astype(np.float32)

    total_len = padded.shape[1]
    num_frames = 1 + (total_len - n_fft) // hop_length
    specs = np.empty((2, n_fft // 2 + 1, num_frames), dtype=np.complex64)

    for ch in range(2):
        stride = padded.strides[1]
        frames = np.lib.stride_tricks.as_strided(
            padded[ch],
            shape=(num_frames, n_fft),
            strides=(hop_length * stride, stride),
        )
        windowed = frames * window[None, :]
        specs[ch] = np.fft.rfft(windowed, n=n_fft, axis=1).T.astype(np.complex64)

    return specs


def istft_numpy(
    specs: np.ndarray,
    length: int,
    n_fft: int = N_FFT,
    hop_length: int = HOP_LENGTH,
) -> np.ndarray:
    pad = n_fft // 2
    num_frames = specs.shape[2]
    out_len = n_fft + hop_length * (num_frames - 1)
    window = np.hanning(n_fft + 1)[:-1].astype(np.float32)
    win_sq = window * window

    out = np.zeros((2, out_len), dtype=np.float32)
    win_sum = np.zeros(out_len, dtype=np.float32)

    for i in range(num_frames):
        pos = i * hop_length
        win_sum[pos:pos + n_fft] += win_sq

    win_sum = np.maximum(win_sum, 1e-8)

    for ch in range(2):
        frames = np.fft.irfft(specs[ch].T, n=n_fft, axis=1).astype(np.float32)
        for i in range(num_frames):
            pos = i * hop_length
            out[ch, pos:pos + n_fft] += frames[i] * window
        out[ch] /= win_sum

    return out[:, pad:pad + length]


def _make_overlap_weight(length: int, transition_power: float = 1.0) -> np.ndarray:
    half = length // 2
    ramp_up = np.arange(1, half + 1, dtype=np.float32)
    ramp_down = np.arange(length - half, 0, -1, dtype=np.float32)
    w = np.concatenate([ramp_up, ramp_down])
    w = (w / float(np.max(w))) ** transition_power
    return w


def separate_track(
    input_wav_path: str,
    output_dir: str,
    model_dir: str,
    onnx_bridge,
    progress_callback=None,
    use_fp16: bool = True,
    overlap: float = 0.25,
) -> dict:
    t_start = time.time()
    os.makedirs(output_dir, exist_ok=True)

    _emit(progress_callback, 1, "[INIT] Запуск StemStudio Python/NumPy Engine (ARM64)...")
    model_path = ensure_model(model_dir, progress_callback, use_fp16=use_fp16)

    _emit(progress_callback, 22, f"[AUDIO] Чтение PCM WAV: {os.path.basename(input_wav_path)}")
    mix, sr = read_wav_stereo_float32(input_wav_path)
    total_samples = mix.shape[1]
    duration_sec = total_samples / float(sr)
    _emit(
        progress_callback,
        24,
        f"[AUDIO] Загружено: {duration_sec:.2f} сек | {sr} Hz | Stereo | {total_samples} сэмплов"
    )

    _emit(progress_callback, 26, f"[DSP] Анализ спектра STFT (n_fft={N_FFT}, hop={HOP_LENGTH})...")
    preview_len = min(total_samples, sr * 4)
    preview_spec = stft_numpy(mix[:, :preview_len], n_fft=N_FFT, hop_length=HOP_LENGTH)
    rms_db = 20.0 * math.log10(max(1e-6, float(np.sqrt(np.mean(mix ** 2)))))
    _emit(
        progress_callback,
        28,
        f"[DSP] Спектр OK: {preview_spec.shape[1]} бинов x {preview_spec.shape[2]} фреймов | RMS: {rms_db:.1f} dBFS"
    )

    use_native_bridge = onnx_bridge is not None
    ort_session = None
    if use_native_bridge:
        _emit(progress_callback, 30, "[ONNX] Инициализация нативной ARM64 OrtSession...")
        onnx_bridge.loadModel(model_path)
        _emit(progress_callback, 33, "[ONNX] Сессия загружена и оптимизирована (CPU/XNNPACK)")
    else:
        import onnxruntime as ort
        _emit(progress_callback, 30, "[ONNX] Инициализация Python onnxruntime...")
        ort_session = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])

    stride = int(SEGMENT_SAMPLES * (1.0 - overlap))
    offsets = list(range(0, max(1, total_samples), stride))
    if len(offsets) > 1 and offsets[-1] + SEGMENT_SAMPLES > total_samples:
        offsets[-1] = max(0, total_samples - SEGMENT_SAMPLES)
        if len(offsets) > 1 and offsets[-1] == offsets[-2]:
            offsets.pop()

    n_chunks = len(offsets)
    _emit(
        progress_callback,
        35,
        f"[AI] Разбиение на {n_chunks} чанк(ов) по 7.8 сек (перекрытие {int(overlap * 100)}%)"
    )

    ref_mono = mix.mean(axis=0)
    ref_mean = float(ref_mono.mean())
    ref_std = float(ref_mono.std()) + 1e-8
    mix_norm = (mix - ref_mean) / ref_std

    out_stems = np.zeros((6, 2, total_samples), dtype=np.float32)
    weight_acc = np.zeros((total_samples,), dtype=np.float32)
    chunk_weight = _make_overlap_weight(SEGMENT_SAMPLES, transition_power=1.0)

    try:
        for idx, offset in enumerate(offsets):
            t_chunk = time.time()
            end = min(offset + SEGMENT_SAMPLES, total_samples)
            actual_len = end - offset

            chunk = np.zeros((2, SEGMENT_SAMPLES), dtype=np.float32)
            chunk[:, :actual_len] = mix_norm[:, offset:end]

            if use_native_bridge:
                flat_in = np.ascontiguousarray(chunk, dtype=np.float32).ravel()
                flat_out = onnx_bridge.runChunk(flat_in)
                stems_chunk = np.asarray(flat_out, dtype=np.float32).reshape(6, 2, SEGMENT_SAMPLES)
            else:
                inp_name = ort_session.get_inputs()[0].name
                stems_chunk = ort_session.run(None, {inp_name: chunk[None, :, :]})[0][0]

            w = chunk_weight[:actual_len]
            out_stems[:, :, offset:end] += stems_chunk[:, :, :actual_len] * w[None, None, :]
            weight_acc[offset:end] += w

            dt = time.time() - t_chunk
            pct = 35 + int(((idx + 1) / n_chunks) * 53)
            _emit(
                progress_callback,
                pct,
                f"[AI] Чанк {idx + 1}/{n_chunks} обработан за {dt:.1f}с (позиция {offset / sr:.1f}с..{end / sr:.1f}с)"
            )
    finally:
        if use_native_bridge:
            onnx_bridge.close()

    weight_acc = np.maximum(weight_acc, 1e-8)
    out_stems /= weight_acc[None, None, :]
    out_stems = out_stems * ref_std + ref_mean

    _emit(progress_callback, 90, "[EXPORT] Сохранение 6 стемов в 16-bit PCM WAV (44.1 kHz Stereo)...")
    result_paths = {}

    for i, stem_name in enumerate(MODEL_STEM_ORDER):
        stem_path = os.path.join(output_dir, f"{stem_name}.wav")
        write_wav_stereo_int16(stem_path, out_stems[i], sr=sr)
        result_paths[stem_name] = stem_path
        pct = 90 + int(((i + 1) / 6.0) * 9)
        size_mb = os.path.getsize(stem_path) / (1024 * 1024)
        _emit(progress_callback, pct, f"[EXPORT] Готов стем '{stem_name}.wav' ({size_mb:.1f} MB)")

    total_time = time.time() - t_start
    _emit(progress_callback, 100, f"[DONE] Разделение завершено за {total_time:.1f} сек! Все 6 дорожек готовы.")
    return result_paths
