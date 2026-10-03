#!/usr/bin/env bash
# A tiny tagged library for local testing: three films (one with a background score), covers, synced lyrics.
set -e
root=${1:?music folder}
song() { # path hz title album artist composer year track
  mkdir -p "$root/$(dirname "$1")"
  ffmpeg -v error -nostdin -y -f lavfi -i "aevalsrc=0.4*sin(2*PI*($2+$2/20*t)*t):s=22050:d=40" -c:a aac -b:a 96k -movflags use_metadata_tags \
    -metadata title="$3" -metadata album="$4" -metadata artist="$5" -metadata album_artist="$6" -metadata composer="$6" \
    -metadata date="$7" -metadata track="$8" -metadata DISCSUBTITLE=Soundtrack -metadata genre=Tamil "$root/$1" </dev/null
}
cover() { mkdir -p "$root/$1"; ffmpeg -v error -nostdin -y -f lavfi -i "color=c=$2:s=500x500" -frames:v 1 "$root/$1/cover.jpg" </dev/null; }
song "Airaa (2019)/01 - Kaariga.m4a" 300 Kaariga Airaa "Sathya Prakash, Chinmayi" "Sundaramurthy K.S." 2019 1
song "Airaa (2019)/02 - Megathoodham.m4a" 420 Megathoodham Airaa "Sid Sriram" "Sundaramurthy K.S." 2019 2
song "Airaa (2019) (Original Background Score)/01 - She Hates You.m4a" 520 "She Hates You" "Airaa (Original Background Score)" "Sundaramurthy K.S." "Sundaramurthy K.S." 2019 1
song "Mouna Raagam (1986)/01 - Mandram Vandha.m4a" 260 "Mandram Vandha" "Mouna Raagam" "S.P. Balasubrahmanyam" Ilaiyaraaja 1986 1
song "Mouna Raagam (1986)/02 - Nilaave Vaa.m4a" 360 "Nilaave Vaa" "Mouna Raagam" "S.P. Balasubrahmanyam" Ilaiyaraaja 1986 2
song "Roja (1992)/01 - Chinna Chinna Aasai.m4a" 600 "Chinna Chinna Aasai" Roja "Minmini" "A.R. Rahman" 1992 1
cover "Airaa (2019)" teal; cover "Airaa (2019) (Original Background Score)" navy; cover "Mouna Raagam (1986)" maroon; cover "Roja (1992)" olive
printf '[00:01.00] Kaariga kaariga\n[00:05.00] Second line\n[00:10.00] Third line\n[00:20.00] Fourth line\n' > "$root/Airaa (2019)/01 - Kaariga.lrc"
echo "library in $root"
