# TONE Streamer

Stream your Android camera over RTSP directly to OBS with TONE!

This is horribly b0rked at the moment, but hey, you can use it if you feel adventurous! It's a minimal streaming app that works as an RTSP server - once you press Stream, it'll open a server (on port 8086 by default, you can change this in settings) to which you can connect with OBS (Media Source plugin - use `rtsp://your_device_ip_addr:port` as the input) or mpv (`rtsp://your_device_ip_addr:port --rtsp-transport=udp`).

But then, it's horribly buggy, so you might want to find something else for now.
