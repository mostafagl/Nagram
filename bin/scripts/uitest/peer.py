"""Second Telegram account on the test DC, used to message the app under test.

Usage:
  peer.py login <phone> <session-file>
  peer.py chat <session-file> <app-phone>      # text, edit, delete, photo
  peer.py send <session-file> <app-phone> <text>
"""
import asyncio
import struct
import sys
import zlib

from telethon import TelegramClient, errors
from telethon.sessions import StringSession
from telethon.tl.functions.contacts import ImportContactsRequest
from telethon.tl.types import InputPhoneContact

# The app's own api credentials (BuildVars); the test DC accepts them.
API_ID = 4
API_HASH = "014b35b6184100b085b0d0572f9b5103"
TEST_DC = (2, "149.154.167.40", 80)


def client_for(session=""):
    client = TelegramClient(StringSession(session), API_ID, API_HASH)
    client.session.set_dc(*TEST_DC)
    return client


async def login(phone, session_file):
    client = client_for()
    await client.connect()
    await client.send_code_request(phone)
    code = phone[5] * 5  # test DC codes: the DC digit five times
    try:
        await client.sign_in(phone, code=code)
    except errors.PhoneNumberUnoccupiedError:
        await client.sign_up(code=code, first_name="Peer", last_name="Tester")
    me = await client.get_me()
    with open(session_file, "w") as f:
        f.write(client.session.save())
    print("peer logged in as", me.id, me.first_name)
    await client.disconnect()


async def target(client, app_phone):
    res = await client(ImportContactsRequest([InputPhoneContact(client_id=0, phone=app_phone, first_name="Parva", last_name="App")]))
    if not res.users:
        raise SystemExit("app account not found by phone " + app_phone)
    return res.users[0]


def png(width=320, height=200):
    rows = b"".join(b"\x00" + b"".join(bytes((x * 255 // width, y * 255 // height, 160)) for x in range(width))
                    for y in range(height))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


async def chat(session_file, app_phone):
    client = client_for(open(session_file).read().strip())
    await client.connect()
    user = await target(client, app_phone)
    await client.send_message(user, "Hello from the test peer")
    await asyncio.sleep(2)
    msg = await client.send_message(user, "Original text before edit")
    await asyncio.sleep(4)
    await client.edit_message(user, msg, "Edited text after the change")
    await asyncio.sleep(2)
    doomed = await client.send_message(user, "This message will be deleted")
    await asyncio.sleep(5)
    await client.delete_messages(user, [doomed.id], revoke=True)
    await asyncio.sleep(2)
    with open("/tmp/peer.png", "wb") as f:
        f.write(png())
    await client.send_file(user, "/tmp/peer.png", caption="A photo for the preview test")
    await client.send_message(user, "Last text message in the chat")
    print("peer chat sent: edit id", msg.id, "deleted id", doomed.id)
    await client.disconnect()


async def send(session_file, app_phone, text):
    client = client_for(open(session_file).read().strip())
    await client.connect()
    user = await target(client, app_phone)
    await client.send_message(user, text)
    print("peer sent:", text)
    await client.disconnect()


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "login":
        asyncio.run(login(sys.argv[2], sys.argv[3]))
    elif cmd == "chat":
        asyncio.run(chat(sys.argv[2], sys.argv[3]))
    elif cmd == "send":
        asyncio.run(send(sys.argv[2], sys.argv[3], sys.argv[4]))
