# Scan to PDF (Android)

App quét tài liệu ra PDF viết bằng Kotlin + Jetpack Compose.

## Tính năng
- Chụp bằng camera, tự nhận diện và cắt viền, chỉnh phối cảnh (ML Kit Document Scanner)
- Quét nhiều trang, sắp xếp, xóa, xoay, thêm trang từ thư viện ảnh
- Lọc ảnh: đen trắng, tăng nét, xóa bóng/vết bẩn
- Xuất 1 file PDF; tùy chọn OCR để PDF tìm kiếm và copy được chữ (gồm tiếng Việt)
- Quét CCCD 2 mặt trên 1 trang A4, chỉnh kích thước 50%-200%
- Chia sẻ qua Zalo, Messenger, Facebook, Bluetooth...

## Build bằng GitHub Actions
1. Tạo repo trên github.com, upload toàn bộ nội dung (kể cả thư mục .github).
2. Vào tab Actions, chạy workflow "Build APK".
3. Tải app-debug.apk ở mục Artifacts, chép sang điện thoại và cài.

## Build bằng Android Studio
Mở thư mục này bằng Android Studio, đợi Gradle Sync, rồi bấm Run.
