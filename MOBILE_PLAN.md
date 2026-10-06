# Kế hoạch chiaki-ng cho điện thoại (Android + iPhone)

Mục tiêu: chơi PS4/PS5 từ xa trên điện thoại qua 4G/Wi-Fi ngoài, đi qua OpenVPN về mạng nhà, kết nối PS5 bằng IP (`192.168.1.73`).

Đánh dấu `[x]` khi xong. Mỗi mục có tiêu chí "Xong khi" để tránh bỏ sót.

## Kiến trúc và ngôn ngữ

| Phần | Android | iPhone |
|---|---|---|
| Lõi giao thức Remote Play | C, dùng chung `lib/` | C, dùng chung `lib/` |
| Giao diện | Kotlin (có sẵn trong `android/`) | Swift + SwiftUI (viết mới trong `ios/`) |
| Giải mã video phần cứng | C + MediaCodec (có sẵn) | Swift + VideoToolbox, hiển thị bằng AVSampleBufferDisplayLayer |
| Âm thanh | C++ + Oboe (có sẵn) | Swift + AVAudioEngine, giải mã Opus bằng libopus |
| Tay cầm | Android InputDevice API (có sẵn) | GameController framework |
| Nút ảo, touchpad | Kotlin (có sẵn) | SwiftUI (viết mới) |
| Build CI | GitHub Actions + Gradle | GitHub Actions (macOS) + XcodeGen + xcodebuild |

Nguyên tắc: không viết lại lõi C, không dùng Flutter/React Native/Qt cho mobile. Mọi thay đổi ở `lib/` phải giữ bản desktop build được.

---

## Giai đoạn 0: Chuẩn bị

### Thông tin cần từ người dùng
- [ ] Phiên bản Android và đời máy Android
- [ ] Đời iPhone và phiên bản iOS
- [ ] Chọn Apple ID miễn phí (ký lại mỗi 7 ngày) hay Apple Developer (99 USD/năm)
- [ ] Đã cài Sideloadly trên PC Windows (cho iOS)
- [ ] Lấy PSN Account ID dạng base64 (xem trong chiaki-ng desktop: Settings → Consoles/PSN, hoặc file cấu hình)
- [ ] Xác nhận PS5 vẫn ở `192.168.1.73` (nên đặt IP tĩnh/DHCP reservation trên router)
- [ ] Xác nhận file `minhquang-phone.ovpn` có `tun-mtu 1400` và đã chạy `tune-openvpn-mtu.ps1` trên server

### Repo
- [ ] Mọi việc làm trên nhánh `new_main` của `NMQuang0801/chiaki-ng`
- [ ] Workflow mới đều có `workflow_call` (input `ref`) để gắn vào bảng chọn `build-mac-intel-windows.yml`

---

## Giai đoạn 1: Android

### 1.1 Khóa ký APK
- [ ] Tạo keystore PKCS12 (alias, mật khẩu) một lần duy nhất, lưu bản sao an toàn ngoài repo
- [ ] Thêm GitHub Secrets: `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- [ ] `android/app/build.gradle`: thêm `signingConfigs.release` đọc từ biến môi trường, không ghi mật khẩu vào repo
- [ ] Xong khi: build release ra APK đã ký, cài đè lên bản cũ không bị lỗi chữ ký

### 1.2 Workflow `build-android.yml`
- [ ] `workflow_dispatch` + `workflow_call` với input `ref`
- [ ] Ubuntu, JDK 17, Android SDK 35, NDK `28.2.13676358`, CMake theo yêu cầu Gradle
- [ ] Checkout `submodules: recursive`
- [ ] Cài protobuf Python (nanopb cần)
- [ ] `./gradlew assembleRelease` (giải mã keystore từ secret vào file tạm)
- [ ] Upload artifact `chiaki-ng-android-Release` chứa file `.apk`
- [ ] Xong khi: chạy tay workflow ra APK tải về được

### 1.3 Gắn vào bảng chọn build
- [ ] Thêm lựa chọn `Android APK | build-android.yml` vào input `target`
- [ ] Thêm job `build-android` (needs `prepare`, `if: endsWith(inputs.target, '| build-android.yml')`)
- [ ] Job `release`: thêm `build-android` vào `needs`, đổi tên file `.apk` theo mẫu `chiaki-ng-android-<ref>-<sha>.apk`
- [ ] Job `notify-telegram`: thêm `build-android` vào `needs`
- [ ] Gửi file APK qua Telegram `sendDocument` khi build thành công (APK dưới 50 MB)
- [ ] Xong khi: chọn Android trong bảng → nhận APK trên Telegram

### 1.4 Sửa lỗi build lần đầu
- [ ] Lỗi Gradle/AGP/Kotlin
- [ ] Lỗi CMake khi biên dịch chéo OpenSSL, curl, json-c, miniupnpc cho Android
- [ ] Lỗi Oboe/NDK
- [ ] Kiểm tra đủ ABI: `arm64-v8a` (bắt buộc), `armeabi-v7a` (tùy chọn), `x86_64` (emulator)
- [ ] Xong khi: build xanh 2 lần liên tiếp

### 1.5 Tiếng Việt
- [ ] Tạo `android/app/src/main/res/values-vi/strings.xml`
- [ ] Dịch toàn bộ `values/strings.xml`, giữ tên riêng: PlayStation, PS4, PS5, PSN, Remote Play, DualSense, DualShock, H264/H265, Wi-Fi
- [ ] Rà chuỗi viết cứng trong file `.kt` và layout `.xml`, chuyển vào `strings.xml`
- [ ] Giữ nguyên placeholder `%s`, `%d`, `%1$s`
- [ ] Xong khi: điện thoại để tiếng Việt thì app hiện tiếng Việt, không còn chuỗi tiếng Anh lọt

### 1.6 Tối ưu cho chơi qua VPN
- [ ] Mặc định 720p, 60 fps, H.265 (nếu máy hỗ trợ giải mã HEVC), bitrate khoảng 8–10 Mbps
- [ ] Nối tùy chọn "xin keyframe khi FEC thất bại" (`enable_idr_on_fec_failure`) từ cài đặt vào JNI
- [ ] Màn hình thêm máy bằng IP: nhập IP/hostname, chọn máy đã đăng ký
- [ ] Đánh thức máy (Wake) qua IP hoạt động khi PS5 ở chế độ nghỉ
- [ ] Hiện thống kê khi chơi (bitrate, mất gói) nếu chưa có
- [ ] Xong khi: đổi cài đặt trong app thì PS5 nhận đúng độ phân giải/bitrate

### 1.7 Test thực tế
- [ ] Ở nhà, cùng Wi-Fi: app tự thấy PS5
- [ ] Đăng ký: nhập PSN Account ID + mã PIN 8 số từ PS5 (Cài đặt → Hệ thống → Remote Play → Liên kết thiết bị)
- [ ] Ở nhà: chơi 10 phút, không vỡ hình
- [ ] Ra ngoài: 4G + OpenVPN Connect, thêm máy bằng `192.168.1.73`, kết nối được
- [ ] Chơi 15 phút qua 4G: ghi lại mất gói, độ trễ, có giật không
- [ ] Tay cầm Bluetooth (DualSense/DualShock) bấm đúng nút, có rung
- [ ] Nút ảo trên màn hình và touchpad dùng được
- [ ] Xoay màn hình, chuyển app ra nền rồi quay lại không crash
- [ ] Ngắt 4G giữa chừng: app báo lỗi rõ ràng, không treo
- [ ] Cài bản build mới đè lên: máy đã đăng ký vẫn còn
- [ ] Xong khi: tất cả mục trên đạt, log lỗi (nếu có) đã gửi và sửa

---

## Giai đoạn 2: iPhone

### 2.1 Build lõi C cho iOS
- [ ] Toolchain CMake cho iOS (`CMAKE_SYSTEM_NAME=iOS`, arm64, deployment target iOS 15+)
- [ ] Dùng mbedtls (`CHIAKI_LIB_ENABLE_MBEDTLS=ON`, `CHIAKI_LIB_MBEDTLS_EXTERNAL_PROJECT=ON`)
- [ ] Tắt phần không cần cho bản đầu: `CHIAKI_ENABLE_RUDP=OFF` (kết nối qua PSN), GUI, CLI, tests, Steam Deck, setsu, FFmpeg
- [ ] Biên dịch libopus cho iOS
- [ ] Kiểm tra `lib/src/thread.c`, `takion.c`, `common.c` với nhánh `__APPLE__` chạy đúng trên iOS
- [ ] Đóng gói thành `ChiakiLib.xcframework` (device arm64 + simulator)
- [ ] Xong khi: một app Swift rỗng link được và gọi `chiaki_lib_init()` thành công

### 2.2 Khung project iOS
- [ ] Thư mục `ios/` với `project.yml` (XcodeGen), bundle ID ví dụ `com.nmquang.chiakiNG`
- [ ] Bridging header / module map để Swift gọi hàm C
- [ ] `Info.plist`: `NSLocalNetworkUsageDescription`, `UIBackgroundModes` (audio nếu cần), hỗ trợ xoay ngang
- [ ] Lưu dữ liệu: danh sách máy, máy đã đăng ký (khóa đăng ký lưu Keychain), cài đặt (UserDefaults)
- [ ] Xong khi: app chạy trên simulator, hiện màn hình chính trống

### 2.3 Màn hình và luồng chính
- [ ] Danh sách máy: thêm/sửa/xóa máy bằng IP hoặc hostname
- [ ] Đăng ký: chọn PS4/PS5, nhập PSN Account ID + PIN, gọi `chiaki_regist_*`
- [ ] Đánh thức máy (`chiaki_discovery_wakeup`)
- [ ] Kiểm tra trạng thái máy bằng discovery gửi thẳng tới IP (unicast)
- [ ] Cài đặt: độ phân giải, FPS, bitrate, codec, xin keyframe khi FEC lỗi, hiện thống kê
- [ ] Xong khi: đăng ký thành công với PS5 thật

### 2.4 Phiên stream
- [ ] Lớp Swift bọc `ChiakiSession`: start/stop, callback video, audio, sự kiện (quit, rumble, trigger)
- [ ] Video: dựng `CMSampleBuffer` từ NAL H.264/H.265, giải mã bằng VideoToolbox, hiển thị bằng `AVSampleBufferDisplayLayer`
- [ ] Xử lý đổi độ phân giải, keyframe, frame lỗi
- [ ] Âm thanh: giải mã Opus → PCM → `AVAudioEngine`, bộ đệm chống giật
- [ ] Micro (tùy chọn, làm sau)
- [ ] Màn hình stream toàn màn hình, giữ tỉ lệ 16:9, ẩn thanh trạng thái, không tự khóa màn hình
- [ ] Lớp thống kê: bitrate, mất gói, độ trễ
- [ ] Xong khi: thấy hình và nghe tiếng từ PS5 ổn định 10 phút

### 2.5 Điều khiển
- [ ] GameController: DualSense, DualShock 4, Xbox, MFi; ánh xạ đủ nút, cần analog, L2/R2
- [ ] Rung tay cầm (CoreHaptics qua `GCController.haptics`)
- [ ] Adaptive trigger của DualSense (`GCDualSenseAdaptiveTrigger`)
- [ ] Touchpad của DualSense/DualShock gửi sang PS
- [ ] Nút ảo trên màn hình: D-pad, 4 nút, 2 cần, L1/R1/L2/R2, Options, Share, PS
- [ ] Vùng touchpad ảo
- [ ] Ẩn nút ảo khi đã kết nối tay cầm
- [ ] Xong khi: chơi được một game thật bằng cả tay cầm và nút ảo

### 2.6 Build CI và cài đặt
- [ ] Workflow `build-ios.yml` (macOS runner): build xcframework → XcodeGen → `xcodebuild archive` với `CODE_SIGNING_ALLOWED=NO`
- [ ] Đóng gói `Payload/chiaki-ng.app` thành `chiaki-ng.ipa`
- [ ] Gắn vào bảng chọn: `iOS IPA (chưa ký) | build-ios.yml`, gửi `.ipa` qua Telegram, đưa lên Release
- [ ] Hướng dẫn ký và cài bằng Sideloadly (Apple ID, bật Developer Mode trên iPhone, tin cậy profile)
- [ ] Xong khi: cài được IPA lên iPhone thật và mở app

### 2.7 Tiếng Việt
- [ ] `Localizable.xcstrings` với tiếng Anh + tiếng Việt, giữ tên riêng như bản desktop
- [ ] Xong khi: iPhone để tiếng Việt thì app hiện tiếng Việt

### 2.8 Test thực tế
- [ ] Cùng các mục ở 1.7, áp dụng cho iPhone
- [ ] Hộp thoại xin quyền "Mạng cục bộ" hiện ra và cho phép được
- [ ] App ra nền rồi quay lại: phiên được đóng/mở lại gọn gàng
- [ ] Ký lại sau 7 ngày (nếu dùng Apple ID miễn phí): dữ liệu vẫn còn

---

## Giai đoạn 3: Sau khi chạy ổn (tùy chọn)

- [ ] Đăng nhập PSN và kết nối qua Internet không cần VPN (cần curl, json-c, miniupnpc cho mobile)
- [ ] Nhiều profile cài đặt (ở nhà / 4G)
- [ ] HDR (iPhone có màn hình HDR, Android hỗ trợ HDR10)
- [ ] Micro gửi sang PS (voice chat)
- [ ] Đồng bộ thêm tính năng từ desktop: tùy chỉnh mất gói tối đa, tự hạ bitrate

---

## Rủi ro và cách xử lý

| Rủi ro | Cách xử lý |
|---|---|
| Biên dịch chéo OpenSSL/curl cho Android lỗi | Đã có commit sửa (2026-02); nếu vẫn lỗi, chuyển sang mbedtls như iOS |
| Mất khóa ký APK | Không cài đè được, phải gỡ app và đăng ký lại; giữ bản sao keystore ở nơi an toàn |
| iOS cấm broadcast nếu không có giấy phép multicast của Apple | Bản iOS thêm máy bằng IP, không tự dò |
| Apple ID miễn phí hết hạn sau 7 ngày | Ký lại bằng Sideloadly, hoặc dùng tài khoản Developer |
| Không test được iOS trên máy của tôi | Mỗi vòng: người dùng cài, chạy, gửi log/mô tả lỗi |
| Sửa `lib/` làm hỏng bản desktop | Sau mỗi thay đổi ở `lib/`, build lại ít nhất một bản desktop (Windows x64 VC hoặc MSYS2) |
| 4G không đủ băng thông | Hạ 720p/6–8 Mbps; kiểm tra MTU VPN đã là 1400 |

## Nhật ký tiến độ

| Ngày | Việc | Kết quả |
|---|---|---|
| | | |
