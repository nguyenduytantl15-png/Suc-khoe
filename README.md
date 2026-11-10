# Sức Khỏe – app Android theo dõi sức khỏe

## Tính năng
- **Đo nhịp tim bằng camera** (PPG): đặt ngón tay che camera sau + đèn flash ~30 giây, xem sóng mạch trực tiếp, tự lưu kết quả và đánh giá.
- **Đếm bước chân** bằng cảm biến phần cứng của máy (đếm cả khi app đóng), mục tiêu bước/ngày, quãng đường (km), calo ước tính.
- **Nhập huyết áp, SpO2** từ máy đo (máy đo huyết áp, máy kẹp ngón) – có phân độ theo khuyến cáo Hội Tim mạch Việt Nam.
- **Cân nặng & BMI** (ngưỡng BMI cho người châu Á).
- **Lịch sử + biểu đồ** cho từng chỉ số; nhấn giữ để xóa bản ghi.
- **Nhắc uống thuốc** (v1.1): đặt nhiều lịch/ngày; thông báo có nút **✅ Đã uống** (app ghi lại giờ uống) và **⏰ Nhắc lại sau 10 phút**; nếu đã ghi uống thì không nhắc nữa để tránh uống trùng; nhật ký 14 ngày ✅/❌.
- **Chữ to** (v1.1): 3 cỡ chữ Vừa / Lớn / Rất lớn (Nhập → Cài đặt).
- **Đếm bước nền** (v1.1): app tự đọc số bước mỗi giờ và lúc 23:57 để bước vào đúng ngày.
- **Xuất Excel & sao lưu** (v1.1): báo cáo .xlsx nhiều trang; sao lưu/khôi phục .json khi đổi máy.
- Dữ liệu chỉ lưu trên máy (SQLite), không gửi đi đâu.

> ⚠️ App chỉ hỗ trợ theo dõi, không thay thế thiết bị y tế hay chẩn đoán của bác sĩ.
> Điện thoại **không thể** đo huyết áp; SpO2 qua camera không đủ chính xác nên app dùng cách nhập tay.

## Cách 1 – Build APK bằng GitHub (làm được trên điện thoại, không cần máy tính)
1. Tạo tài khoản và một repository mới (private cũng được) trên github.com.
2. Giải nén file zip, tải **toàn bộ** nội dung lên repo (nút *Add file → Upload files*; nhớ có cả thư mục `.github`).
   - Nếu trình duyệt điện thoại không tải được thư mục `.github`: vào *Add file → Create new file*, đặt tên `.github/workflows/main.yml` và dán nội dung file đó vào.
3. Vào tab **Actions** → chọn *Build APK* → *Run workflow* (hoặc nó tự chạy sau khi tải lên). Đợi ~5 phút.
4. Mở lần chạy đã xong (dấu ✓ xanh) → phần **Artifacts** → tải `SucKhoe-apk` (file zip chứa `app-debug.apk`).
5. Giải nén, mở `app-debug.apk` trên điện thoại → cho phép "Cài đặt ứng dụng không rõ nguồn gốc" → Cài đặt.

## Cách 2 – Android Studio (trên máy tính)
1. Mở Android Studio → *Open* → chọn thư mục `SucKhoe`.
2. Đợi Gradle đồng bộ xong → *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
3. File APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

Hoặc dòng lệnh (cần JDK 17 + Android SDK): `./gradlew assembleDebug`

## Yêu cầu
- Android 7.0 (API 24) trở lên. Đo nhịp tim cần camera sau + đèn flash; đếm bước cần cảm biến bước chân (đa số máy có).

## Cấu trúc mã
| File | Nội dung |
|---|---|
| `MainActivity.kt` | Giao diện 4 tab: Tổng quan, Đo nhịp tim, Nhập chỉ số, Lịch sử |
| `HeartRateMonitor.kt` | Camera2 + đèn flash, lấy độ sáng từng khung hình |
| `SignalProc.kt` | Lọc tín hiệu và tính nhịp tim bằng tự tương quan |
| `StepTracker.kt` | Cảm biến đếm bước |
| `Health.kt` | Ngưỡng đánh giá nhịp tim, huyết áp, SpO2, BMI |
| `Db.kt` | Lưu dữ liệu SQLite, cài đặt |
| `Charts.kt` | Biểu đồ đường, cột, vòng tiến độ, sóng mạch |
