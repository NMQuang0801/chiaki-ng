# Kế hoạch chiaki-ng cho điện thoại (Android + iPhone)

Mục tiêu: chơi PS4/PS5 từ xa trên điện thoại qua 4G/Wi-Fi ngoài, đi qua OpenVPN về mạng nhà, kết nối PS5 bằng IP (`192.168.1.73`).

Đánh dấu `[x]` khi xong. Mỗi mục có tiêu chí "Xong khi" để tránh bỏ sót.

## Kiến trúc và ngôn ngữ


| Phần                      | Android                          | iPhone                                                         |
| ------------------------- | -------------------------------- | -------------------------------------------------------------- |
| Lõi giao thức Remote Play | C, dùng chung `lib/`             | C, dùng chung `lib/`                                           |
| Giao diện                 | Kotlin (có sẵn trong `android/`) | Swift + SwiftUI (viết mới trong `ios/`)                        |
| Giải mã video phần cứng   | C + MediaCodec (có sẵn)          | Swift + VideoToolbox, hiển thị bằng AVSampleBufferDisplayLayer |
| Âm thanh                  | C++ + Oboe (có sẵn)              | Swift + AVAudioEngine, giải mã Opus bằng libopus               |
| Tay cầm                   | Android InputDevice API (có sẵn) | GameController framework                                       |
| Nút ảo, touchpad          | Kotlin (có sẵn)                  | SwiftUI (viết mới)                                             |
| Build CI                  | GitHub Actions + Gradle          | GitHub Actions (macOS) + XcodeGen + xcodebuild                 |


Nguyên tắc: không viết lại lõi C, không dùng Flutter/React Native/Qt cho mobile. Mọi thay đổi ở `lib/` phải giữ bản desktop build được.

## Phiên bản tối thiểu


|         | Tối thiểu        | Máy chạy được                                                   | Vì sao không thấp hơn                                                                                                                                                                                                                                                   |
| ------- | ---------------- | --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android | **7.0 (API 24)** | Gần như mọi máy Android từ 2016 trở lại đây                     | Lõi C dùng `getifaddrs()` (Android chỉ có từ API 24) để dò máy và kết nối PSN; phần video dùng `AMediaCodec_setOutputSurface()` (từ API 23). Hạ xuống 5.0/6.0 phải viết lại hai chỗ này mà chỉ thêm được máy đời 2014–2015, phần lớn không giải mã H.265 bằng phần cứng |
| iOS     | **15.0**         | iPhone 6s, 6s Plus, SE (đời 1), 7, 7 Plus và mọi iPhone mới hơn | Mọi máy chạy được iOS 13/14 đều lên được iOS 15, nên hạ xuống 13/14 không thêm máy nào. Hạ xuống iOS 12 chỉ thêm iPhone 5s/6/6 Plus: không có SwiftUI, không giải mã H.265 bằng phần cứng, RAM 1 GB, không hỗ trợ tay cầm DualSense/DualShock                           |


Tính năng phụ thuộc phiên bản (app vẫn chạy, chỉ tắt tính năng đó):

- iOS dưới 14.5: không dùng được tay cầm DualSense (vẫn dùng nút ảo, tay cầm MFi, DualShock 4)
- Android dưới 8.0: rung dùng kiểu cũ, không chỉnh được độ mạnh
- Máy không giải mã H.265 bằng phần cứng: chọn H.264 trong cài đặt

---

## Giai đoạn 0: Chuẩn bị

### Thông tin cần từ người dùng

- [x] Phiên bản Android: **Android 11** (API 30, app hỗ trợ từ API 24)
- [x] Đời iPhone và phiên bản iOS: người dùng ghi "iOS 24", không có phiên bản này (Apple nhảy từ iOS 18 lên iOS 26). Cần kiểm tra lại trong Cài đặt → Cài đặt chung → Giới thiệu
- [x] Chọn **Apple ID miễn phí**, người dùng tự ký lại mỗi 7 ngày
- [x] Đã cài Sideloadly trên PC Windows
- [ ] Lấy PSN Account ID dạng base64. Cách lấy:
  - Desktop chiaki-ng: hộp thoại đăng ký máy có nút **PSN Login** (đăng nhập tài khoản Sony) và **Public Lookup** (tra theo Online ID). Ô Account ID tự điền, copy ra dùng cho điện thoại
  - App Android hiện chỉ cho nhập tay (mục 1.8 sẽ thêm cách lấy trong app)
- [x] PS5 vẫn ở `192.168.1.73` (nên đặt DHCP reservation trên router để IP không đổi)
- [x] Điện thoại đã dùng cấu hình VPN mới (`tun-mtu 1400`)

### Repo

- [x] Mọi việc làm trên nhánh `new_main` của `NMQuang0801/chiaki-ng`
- [x] Workflow mới đều có `workflow_call` (input `ref`) để gắn vào bảng chọn `build-mac-intel-windows.yml`

---

## Giai đoạn 1: Android

### 1.1 Khóa ký APK

- [x] Tạo keystore PKCS12 một lần duy nhất, lưu ngoài repo tại `Documents\chiaki-ng-android-signing` (RSA 4096, hạn 40 năm)
- [x] Người dùng thêm GitHub Secrets: `ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (giá trị trong `README.txt` của thư mục trên)
- [x] Sao lưu thư mục `chiaki-ng-android-signing` sang nơi an toàn khác (USB, Google Drive riêng tư)
- [x] `android/app/build.gradle`: đọc khóa từ `local.properties` do CI ghi (thêm `chiakiKeystoreType=pkcs12`); chưa có khóa thì ký bằng khóa debug để vẫn cài thử được
- [x] `versionCode` lấy theo số lần chạy workflow để bản mới luôn cài đè được
- [x] Xong khi: build release ra APK đã ký, cài đè lên bản cũ không bị lỗi chữ ký

### 1.2 Workflow `build-android.yml`

- [x] `workflow_dispatch` + `workflow_call` với input `ref`
- [x] Ubuntu, JDK 17, Android SDK 35, NDK `28.2.13676358`, CMake 3.22.1
- [x] Checkout `submodules: recursive`
- [x] Cài protobuf Python (nanopb cần)
- [x] `gradle assembleRelease` bằng Gradle 8.11.1 (repo không có `gradle-wrapper.jar` nên không dùng `./gradlew`), giải mã keystore từ secret vào file tạm
- [x] Chỉ build ABI `arm64-v8a` và `armeabi-v7a` cho nhanh
- [x] Upload artifact `chiaki-ng-android-Release` chứa file `.apk`
- [ ] Xong khi: chạy tay workflow ra APK tải về được

### 1.3 Gắn vào bảng chọn build

- [x] Thêm lựa chọn `Android APK | build-android.yml` vào input `target`
- [x] Thêm job `build-android` (needs `prepare`, `secrets: inherit` để đọc khóa ký)
- [x] Job `release`: thêm `build-android` vào `needs`, đổi tên file `.apk`
- [x] Job `notify-telegram`: thêm `build-android` vào `needs`
- [x] Gửi file APK qua Telegram `sendDocument` khi build thành công (APK dưới 49 MB)
- [ ] Xong khi: chọn Android trong bảng → nhận APK trên Telegram

### 1.4 Sửa lỗi build lần đầu

- [ ] Lỗi Gradle/AGP/Kotlin
- [ ] Lỗi CMake khi biên dịch chéo OpenSSL, curl, json-c, miniupnpc cho Android
- [ ] Lỗi Oboe/NDK
- [ ] Kiểm tra đủ ABI: `arm64-v8a` (bắt buộc), `armeabi-v7a` (tùy chọn), `x86_64` (emulator)
- [ ] Xong khi: build xanh 2 lần liên tiếp

### 1.5 Tiếng Việt

- [x] Tạo `android/app/src/main/res/values-vi/strings.xml`
- [x] Dịch toàn bộ `values/strings.xml`, giữ tên riêng: PlayStation, PS4, PS5, PSN, Remote Play, DualSense, DualShock, H264/H265, Wi-Fi
- [x] Rà chuỗi viết cứng trong file `.kt` và layout `.xml` (không có chuỗi nào cần dịch)
- [x] Giữ nguyên placeholder `%s`, `%d` (đã kiểm tra tự động)
- [ ] Lý do kết thúc phiên ("Session has quit: ...") vẫn là tiếng Anh từ lõi C, cần bảng dịch phía Android
- [ ] Xong khi: điện thoại để tiếng Việt thì app hiện tiếng Việt, không còn chuỗi tiếng Anh lọt

### 1.6 Tối ưu cho chơi qua VPN

- [x] Mặc định 720p, 60 fps, H.265, bitrate tự động 10 Mbps (app đã đặt sẵn như vậy)
- [x] Nối tùy chọn "Xin keyframe khi mất gói" (`enable_idr_on_fec_failure`) từ cài đặt vào JNI, mặc định bật
- [x] Sửa lỗi `packet_loss_max = 0` trong JNI: trước đây app luôn báo PS5 mất 0% gói nên PS5 không tự hạ bitrate khi mạng yếu. Nay dùng 5% như bản desktop
- [x] Màn hình thêm máy bằng IP: nhập IP/hostname, chọn máy đã đăng ký (app có sẵn: + → Thêm máy thủ công)
- [x] Tự nhớ IP của PS5 đã đăng ký khi tìm thấy trong mạng nhà, để ra 5G + VPN vẫn hiện máy
- [x] Sửa lỗi đăng ký với Broadcast báo "Invalid argument" (lõi C gửi tới cổng 0)
- [ ] Đánh thức máy (Wake) qua IP hoạt động khi PS5 ở chế độ nghỉ
- [ ] Hiện thống kê khi chơi (bitrate, mất gói) nếu chưa có
- [ ] Xong khi: đổi cài đặt trong app thì PS5 nhận đúng độ phân giải/bitrate

### 1.7 Test thực tế

- [ ] Ở nhà, cùng Wi-Fi: app tự thấy PS5
- [ ] Đăng ký: nhập PSN Account ID + mã PIN 8 số từ PS5 (Cài đặt → Hệ thống → Remote Play → Liên kết thiết bị)
- [ ] Ở nhà: chơi 10 phút, không vỡ hình
- [ ] Ra ngoài: 4G + OpenVPN Connect, thêm máy bằng `192.168.1.73`, kết nối được
- [ ] Chơi 15 phút qua 4G: ghi lại mất gói, độ trễ, có giật không
- [x] Tay cầm Bluetooth (DualSense/DualShock) bấm đúng nút (DualSense Bluetooth trên Android 11 đã test)
- [x] Nút ảo trên màn hình và touchpad dùng được
- [ ] Xoay màn hình, chuyển app ra nền rồi quay lại không crash
- [ ] Ngắt 4G giữa chừng: app báo lỗi rõ ràng, không treo
- [ ] Cài bản build mới đè lên: máy đã đăng ký vẫn còn
- [ ] Xong khi: tất cả mục trên đạt, log lỗi (nếu có) đã gửi và sửa

### 1.7b Rung, đèn, Adaptive trigger của DualSense

Android không có API cho Adaptive trigger. Qua Bluetooth chỉ làm được khi root, nên Adaptive trigger chỉ có khi cắm dây USB.

- [x] Bật chế độ DualSense khi kết nối PS5 (`enable_dualsense`), có công tắc tắt trong cài đặt
- [x] Chuyển sự kiện cò, đèn, số người chơi, cường độ, rung haptic từ lõi C sang app; đổi rung haptic thành mức rung
- [x] Android 12+: rung và đèn trên tay cầm Bluetooth (`VibratorManager`, `LightsManager`)
- [x] Android 11 trở xuống: rung điện thoại như cũ
- [x] DualSense qua cáp USB: xin quyền trước khi vào stream, tự đọc nút/cần/cò/touchpad, gửi Adaptive trigger, rung, đèn, đèn người chơi
- [x] Phiên chơi không bị ngắt khi hộp thoại xin quyền USB hiện lên (chuyển sang `onStart`/`onStop`)
- [x] Con quay hồi chuyển (gyro) của DualSense qua USB, tính hướng bằng `chiaki_orientation_tracker` như bản desktop
- [x] Rung haptic thật: phát haptic ra kênh 3–4 của thiết bị âm thanh USB của DualSense (48 kHz, 4 kênh), có công tắc tắt
- [ ] Kiểm tra trên máy thật: điện thoại có mở được luồng âm thanh 4 kênh tới DualSense không (tùy hãng)
- [ ] Khi cắm USB, Android có thể chuyển tiếng game sang loa/jack tai nghe của DualSense: cân nhắc thêm tùy chọn giữ tiếng ở điện thoại
- [ ] Xong khi: cắm DualSense vào điện thoại, chơi game có Adaptive trigger (Astro's Playroom, Returnal...) thấy cò cứng/rung

### 1.8 Lấy PSN Account ID ngay trong app (làm sau khi 1.7 chạy được)

- [ ] Nút **Tra theo Online ID**: gọi `https://psn.flipscreen.games/search.php?username=...` như bản desktop, tự điền Account ID (dịch vụ bên thứ ba, không cần mật khẩu)
- [ ] Nút **Đăng nhập PSN**: mở trang đăng nhập Sony trong WebView, nhận mã từ URL chuyển hướng, đổi lấy Account ID (giống luồng PSN Login của desktop)
- [ ] Lưu Account ID để lần đăng ký sau tự điền
- [ ] Xong khi: đăng ký PS5 trên điện thoại không cần copy Account ID từ máy tính

---

## Giai đoạn 2: iPhone

### 2.1 Build lõi C cho iOS

- [ ] Toolchain CMake cho iOS (`CMAKE_SYSTEM_NAME=iOS`, arm64, deployment target iOS 15.0)
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


| Rủi ro                                                       | Cách xử lý                                                                               |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| Biên dịch chéo OpenSSL/curl cho Android lỗi                  | Đã có commit sửa (2026-02); nếu vẫn lỗi, chuyển sang mbedtls như iOS                     |
| Mất khóa ký APK                                              | Không cài đè được, phải gỡ app và đăng ký lại; giữ bản sao keystore ở nơi an toàn        |
| iOS cấm broadcast nếu không có giấy phép multicast của Apple | Bản iOS thêm máy bằng IP, không tự dò                                                    |
| Apple ID miễn phí hết hạn sau 7 ngày                         | Ký lại bằng Sideloadly, hoặc dùng tài khoản Developer                                    |
| Không test được iOS trên máy của tôi                         | Mỗi vòng: người dùng cài, chạy, gửi log/mô tả lỗi                                        |
| Sửa `lib/` làm hỏng bản desktop                              | Sau mỗi thay đổi ở `lib/`, build lại ít nhất một bản desktop (Windows x64 VC hoặc MSYS2) |
| 4G không đủ băng thông                                       | Hạ 720p/6–8 Mbps; kiểm tra MTU VPN đã là 1400                                            |


## Nhật ký tiến độ


| Ngày       | Việc                                                                                         | Kết quả                                           |
| ---------- | -------------------------------------------------------------------------------------------- | ------------------------------------------------- |
| 2026-10-07 | Tạo khóa ký APK, workflow `build-android.yml`, gắn vào bảng chọn build, gửi APK qua Telegram | Chờ người dùng thêm secrets và chạy build lần đầu |
| 2026-10-07 | Tiếng Việt cho app Android, tùy chọn xin keyframe khi mất gói, sửa `packet_loss_max = 0`     | Chờ build kiểm tra                                |
| 2026-10-07 | Sửa CI: bỏ kiểm tra wrapper jar của oboe, cài `protoc` 29.3 | Build APK thành công, đăng ký PS5 thành công trên điện thoại thật (Android 11), xem được hình |
| 2026-10-07 | Sửa nút ảo không hoạt động; thêm "Kiểu tay cầm", tự nhận DualSense/DualShock bị Android ánh xạ thô (DualSense Bluetooth trên Android 11 bị lệch nút); sửa công tắc "Xin keyframe khi mất gói" không lưu | Đã test OK: nút ảo và DualSense Bluetooth bấm đúng |
| 2026-10-07 | Chế độ DualSense, rung/đèn tay cầm cho Android 12+, DualSense qua USB có Adaptive trigger | Chờ build và test |
| 2026-10-07 | DualSense qua USB: gyro của tay cầm, rung haptic thật qua kênh âm thanh | Chờ build và test |


