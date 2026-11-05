"""La voz de ElevenLabs, de ida y de vuelta. La clave vive aquí, en el servidor; nunca llega al navegador.

- Lo que dice JARVIS: texto a voz (eleven_flash_v2_5, language_code "es").
- Lo que dices tú:  Scribe (/v1/speech-to-text, model_id scribe_v1, language_code "es").
"""
import json
import os
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("ELEVENLABS_BASE_URL", "https://api.elevenlabs.io").rstrip("/")
TTS_MODEL = "eleven_flash_v2_5"
STT_MODEL = "scribe_v1"
LANG = "es"
TIMEOUT = 30

STATUS = {"voice": "sin probar", "detail": ""}
_voice_cache = {}


class VoiceError(Exception):
    pass


def key():
    return os.environ.get("ELEVENLABS_API_KEY", "").strip()


def configured():
    return bool(key())


def _request(method, path, body=None, headers=None, timeout=TIMEOUT):
    if not key():
        raise VoiceError("falta ELEVENLABS_API_KEY en .env")
    h = {"xi-api-key": key()}
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read(), r.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        detail = e.read()[:300].decode("utf-8", "replace")
        try:
            d = json.loads(detail).get("detail")
            detail = d.get("message", d) if isinstance(d, dict) else d
        except Exception:
            pass
        raise VoiceError(f"ElevenLabs {e.code}: {detail}")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise VoiceError(f"sin conexión con ElevenLabs ({getattr(e, 'reason', e)})")


def voice_id():
    v = os.environ.get("ELEVENLABS_VOICE_ID", "").strip()
    if v:
        return v
    if "auto" in _voice_cache:
        return _voice_cache["auto"]
    # Sin voz elegida: la primera voz disponible en tu cuenta
    raw, _ = _request("GET", "/v1/voices")
    voices = json.loads(raw).get("voices") or []
    if not voices:
        raise VoiceError("tu cuenta de ElevenLabs no tiene voces; pon ELEVENLABS_VOICE_ID en .env")
    _voice_cache["auto"] = voices[0]["voice_id"]
    return _voice_cache["auto"]


def speak(text):
    """Texto -> (audio, content-type)."""
    text = (text or "").strip()[:1000]
    if not text:
        raise VoiceError("texto vacío")
    try:
        body = json.dumps({"text": text, "model_id": TTS_MODEL, "language_code": LANG}).encode()
        audio, ctype = _request("POST", f"/v1/text-to-speech/{voice_id()}?output_format=mp3_44100_128", body,
                                {"Content-Type": "application/json", "Accept": "audio/mpeg"})
        STATUS.update(voice="lista", detail="")
        return audio, (ctype if ctype.startswith("audio/") else "audio/mpeg")
    except VoiceError as e:
        STATUS.update(voice="error", detail=str(e))
        raise


def listen(audio, content_type="audio/webm"):
    """Audio grabado en el navegador -> texto (Scribe)."""
    if not audio:
        raise VoiceError("audio vacío")
    ext = {"audio/webm": "webm", "audio/ogg": "ogg", "audio/mp4": "m4a", "audio/mpeg": "mp3",
           "audio/wav": "wav"}.get(content_type.split(";")[0].strip(), "webm")
    boundary = "jarvis" + uuid.uuid4().hex
    parts = []
    for name, value in (("model_id", STT_MODEL), ("language_code", LANG), ("tag_audio_events", "false")):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="voz.{ext}"\r\n'
                 f'Content-Type: {content_type.split(";")[0]}\r\n\r\n'.encode() + audio + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    try:
        raw, _ = _request("POST", "/v1/speech-to-text", b"".join(parts),
                          {"Content-Type": f"multipart/form-data; boundary={boundary}"})
        STATUS.update(voice="lista", detail="")
        return (json.loads(raw).get("text") or "").strip()
    except VoiceError as e:
        STATUS.update(voice="error", detail=str(e))
        raise
