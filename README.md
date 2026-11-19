# Đọc Sách — ứng dụng Android

Vỏ ứng dụng Android cho web **Đọc Sách Bằng Giọng Nói**
(https://doc-sach.nguyenhailinh140101.workers.dev/).

Ngoài việc mở web toàn màn hình như một ứng dụng riêng, vỏ này làm 3 việc
mà trang web tự nó không làm được trên Android:

1. **Chạy nền thật**: một dịch vụ nền (foreground service, loại phát
   media) giữ ứng dụng sống khi tắt màn hình hoặc chuyển sang ứng dụng
   khác; WebView không bị tạm dừng nên giọng đọc chạy tiếp. Có thông báo
   thường trực "Đang chạy nền" — chạm vào là quay lại ứng dụng.
2. **Chọn file thật** cho nút thêm sách (PDF/Word/EPUB/ảnh…).
3. **Tải xuống thật** (sách nói/văn bản xuất ra) vào thư mục Downloads
   qua DownloadManager của máy.

Quyền của ứng dụng: INTERNET, WAKE_LOCK (giữ máy thức vừa đủ để phát
tiếp), FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PLAYBACK (dịch vụ
nền), POST_NOTIFICATIONS (hiện thông báo chạy nền).

Build bằng GitHub Actions, không Gradle: aapt2 → javac → d8 → zipalign →
apksigner (khóa debug tự sinh). Package: `com.docsach.app`.
