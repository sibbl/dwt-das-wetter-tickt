#!/bin/bash
IP="${1:-192.168.0.132}"
echo "Searching for Wear OS ADB services for $IP via mDNS..."
SERVICES=$(adb mdns services | grep "$IP")

if [ -z "$SERVICES" ]; then
    echo "No adb services discovered via MDNS for $IP."
    echo "Make sure Wireless Debugging is enabled in Developer Options on the watch."
    exit 1
fi

# Disconnect existing connections
adb disconnect

# Connect to all advertised ports for the IP
for addr in $(echo "$SERVICES" | awk '{print $NF}'); do
    if [[ "$addr" == *":"* ]]; then
        echo "Connecting to $addr..."
        adb connect "$addr"
    fi
done

# Ensure the APK exists, otherwise build it
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "APK not found at $APK_PATH. Building debug APK first..."
    ./gradlew assembleDebug
fi

# Install the APK
echo "Installing to connected watch..."
adb install -r "$APK_PATH"
