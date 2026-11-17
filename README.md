# Scan to PDF (Android)

App quét tài liệu ra PDF viết bằng Kotlin + Jetpack Compose.

## Tính năng
- Chụp bằng camera, tự nhận diện và cắt viền, chỉnh phối cảnh (ML Kit Document Scanner)
- Quét nhiều trang, sắp xếp, xóa, xoay, thêm trang từ thư viện ảnh
- Lọc ảnh: đen trắng, tăng nét, xóa bóng/vết bẩn
- Xuất 1 file PDF; tùy chọn OCR để PDF tìm kiếm và copy được chữ (gồm tiếng Việt)
- Danh sách PDF đã quét: mở, chia sẻ, xóa

## Cài đặt và build (lần đầu)
1. Tải và cài **Android Studio** (bản mới nhất): https://developer.android.com/studio
2. Mở Android Studio → **Open** → chọn thư mục `ScanToPdf`.
3. Đợi **Gradle Sync** chạy xong (cần internet, lần đầu mất vài phút).
4. Chạy trên điện thoại thật:
   - Điện thoại: Cài đặt → Thông tin điện thoại → bấm 7 lần vào **Số bản dựng** → bật **Tùy chọn nhà phát triển** → bật **Gỡ lỗi USB**.
   - Cắm cáp USB, chọn "Cho phép gỡ lỗi".
   - Trong Android Studio, chọn điện thoại ở thanh trên rồi bấm nút **Run ▶**.
5. Muốn có file APK để gửi cho người khác: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

## Lưu ý
- Máy quét cần **Google Play Services** (hầu hết điện thoại Android đều có). Lần quét đầu tiên máy có thể tải thêm module, cần có mạng.
- Không cần xin quyền camera: màn hình quét do Google Play Services xử lý.
- PDF lưu trong bộ nhớ riêng của app (`files/scans`). Dùng nút **Chia sẻ** để gửi/lưu ra nơi khác.
- OCR dùng bộ nhận dạng chữ Latin của ML Kit, đọc được tiếng Việt có dấu nhưng độ chính xác phụ thuộc chất lượng ảnh.

## Cấu trúc
- `app/src/main/java/com/example/scantopdf/MainActivity.kt` — giao diện, luồng quét, lưu/mở/chia sẻ
- `app/src/main/java/com/example/scantopdf/OcrPdf.kt` — tạo PDF có lớp chữ OCR
- `app/build.gradle.kts` — thư viện sử dụng
