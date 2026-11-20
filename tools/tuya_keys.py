"""
Finds what Free My TV needs to drive a Tuya light on the local network (LSC Smart Connect from Action, Smart Life and
most cheap Wi-Fi lights): its device id, local key, local IP, protocol version and data points. Run it once on a
computer on the same Wi-Fi as the light:

    py -m pip install tuya-device-sharing-sdk tinytuya qrcode pillow      (Windows; python3 -m pip elsewhere)
    py tools/tuya_keys.py

It asks for the Smart Life "user code" (Smart Life > Me > gear > Account and Security > User Code), shows a QR code to
scan with Smart Life, then lists the lights. No Tuya developer account: it logs in the way Home Assistant's own Tuya
integration does (Tuya's official device-sharing SDK and Home Assistant's public app registration), then signs out.

The local key is a secret: it goes into the repository's secrets (TUYA_KEY), never in a chat, an issue or a commit.
"""
import os
import sys
import time

try:
    import qrcode
    import tinytuya
    from tuya_sharing import LoginControl, Manager
except ImportError:
    sys.exit("Missing modules. First: py -m pip install tuya-device-sharing-sdk tinytuya qrcode pillow")

CLIENT_ID = "HA_3y9q4ak7g4ephrvke"   # Home Assistant's Tuya integration
SCHEMA = "haauthorize"


def login(user_code):
    lc = LoginControl()
    r = lc.qr_code(CLIENT_ID, SCHEMA, user_code)
    if not r.get("success"):
        sys.exit("Tuya refused the user code: " + str(r.get("msg", r)))
    token = r["result"]["qrcode"]
    img = os.path.abspath("tuya-qr.png")
    qrcode.make("tuyaSmart--qrLogin?token=" + token).save(img)
    print("\nScan this QR code with Smart Life (+ at the top right > Scan), then confirm in the app.")
    print("The picture is in " + img)
    if sys.platform.startswith("win"):
        os.startfile(img)
    else:
        q = qrcode.QRCode(border=1)
        q.add_data("tuyaSmart--qrLogin?token=" + token)
        q.print_ascii(invert=True)
    for _ in range(100):   # about 5 minutes
        time.sleep(3)
        ok, info = lc.login_result(token, CLIENT_ID, user_code)
        if ok:
            try:
                os.remove(img)
            except OSError:
                pass
            return info
    sys.exit("No confirmation in Smart Life: run it again.")


def main():
    user_code = input("Smart Life user code (Me > gear > Account and Security > User Code): ").strip()
    info = login(user_code)
    manager = Manager(CLIENT_ID, user_code, info["terminal_id"], info["endpoint"], info)
    try:
        manager.update_device_cache()
        devices = list(manager.device_map.values())
    finally:
        try:
            manager.unload()   # signs this computer out of the account
        except Exception:
            pass
    if not devices:
        sys.exit("No device in this Smart Life account. Is the LED strip added to Smart Life (not only LSC Smart Connect)?")

    print("\nLooking for them on the Wi-Fi (about 20 s)...")
    found = {}
    try:
        for ip, d in tinytuya.deviceScan(verbose=False, maxretry=8).items():
            found[d.get("gwId") or d.get("id")] = (d.get("ip") or ip, str(d.get("version") or d.get("ver") or ""))
    except Exception as e:
        print("Couldn't scan the Wi-Fi: " + str(e))

    for d in devices:
        ip, version = found.get(d.id, ("not seen on this Wi-Fi", "?"))
        print("\n" + "=" * 60)
        print(d.name + "  (" + d.category + ", " + d.product_name + ")")
        print("  TUYA_ID      " + d.id)
        print("  TUYA_KEY     " + d.local_key + "    <- secret")
        print("  TUYA_IP      " + ip)
        print("  TUYA_VERSION " + version)
        points = sorted((int(k), v.get("status_code", "?")) for k, v in (d.local_strategy or {}).items())
        if points:
            print("  Data points (fine to share): " + ", ".join(str(k) + "=" + c for k, c in points))
        elif d.status:
            print("  Status codes (fine to share): " + ", ".join(sorted(d.status)))
    print("\nPut TUYA_ID, TUYA_KEY, TUYA_IP and TUYA_VERSION in GitHub (Settings > Secrets and variables > Actions).")
    print("Share only the data points line in the chat, never the key.")


if __name__ == "__main__":
    main()
