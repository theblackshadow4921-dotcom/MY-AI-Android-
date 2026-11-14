import subprocess

print("MY PERSONAL VOICE ASSISTANT")
print("Microphone starting...")

while True:
    try:
        print("\nSpeak now...")

        result = subprocess.run(
            ["termux-speech-to-text"],
            capture_output=True,
            text=True
        )

        answer = result.stdout.strip()

        if not answer:
            print("Voice not detected")
            continue

        print("You:", answer)

        if answer.lower() in ["exit", "stop", "band karo"]:
            subprocess.run(["termux-tts-speak", "Goodbye"])
            break

        subprocess.run(
            ["termux-tts-speak", answer]
        )

    except Exception as error:
        print("Error:", error)
        break
