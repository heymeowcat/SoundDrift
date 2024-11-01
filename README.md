# SoundDrift
Real-time Audio Streaming from Android to Your Desktop SoundDrift lets you stream audio from your Android device directly to your desktop in  real-time.

<img src="https://github.com/user-attachments/assets/117ffebd-0cda-413a-8aa7-2a3f9da04fd5" width="300"/>

## Usage
Download the [Latest Release](https://github.com/heymeowcat/SoundDrift/releases/latest)

## Features

1. **Low-Latency Streaming:** Experience minimal delay, perfect for real-time audio sharing. 
2. **Metadata Display:** The client displays device information, latency, and buffer statistics for monitoring stream health.
3. **Microphone & Device Audio:** Stream from your microphone, device audio, or both simultaneously.
4. **Individual Volume Control:** Adjust the volume of your microphone and device audio independently. 

## Architecture 

This project consists of two components: 
1. **Android Server (SoundDrift):** An Android app that captures audio and streams it over the network. 
2. **Electron Client (SoundDrift Player):** A desktop application that receives and plays the audio stream. 

## Prerequisites 

- **Android Studio:** For building and running the Android app. 
- **Node.js and npm:** For installing Electron and its dependencies. 

## Installation 

### Android Server 
1. Clone the repository:
 `git clone https://github.com/your-username/SoundDrift.git` 
 2. Open the project in Android Studio. 
 3. Build and run the app on your device. 
 
### Client  [SoundDrift Player Client](https://github.com/heymeowcat/SoundDriftPlayer)
1. Clone the repository `git clone https://github.com/heymeowcat/SoundDriftPlayer.git`
2. Install dependencies: `npm install` 
3. Start the Electron app: `npm start` 

## Usage 
1. Connect your Android device and desktop to the same Wi-Fi network.
2. Launch the SoundDrift app on your Android device.
3. Enter your Android device's IP address into the Electron client. (You can find this in your Android device's Wi-Fi settings.) 
4. Click "Connect" on the Electron client.
5. Adjust the microphone and device audio settings on the Android app.
6. Enjoy real-time audio streaming!

## Notes

 - Ensure that the firewall on your desktop allows incoming connections
   on port 55555 (UDP) and 55557 (TCP).  
 - The app requests permission to    record audio, which is essential
   for its functionality.

## Contributing 
Contributions are welcome! If you find any issues or have suggestions for improvement, feel free to create a pull request.
