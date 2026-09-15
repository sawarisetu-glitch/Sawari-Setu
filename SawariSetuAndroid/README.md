# सवारी सेतु — Android Demo

यह Android project सवारी सेतु के वर्तमान demo को APK में build करने के लिए तैयार है.

## शामिल सुविधाएँ
- Customer / Driver / Admin demo
- Share Ride / Direct Ride
- ई-रिक्शा, ऑटो, बाइक, कार
- Booking flow
- Driver online/offline
- Accept / Reject / Start / Complete
- Earnings और Admin summary
- Final Sawari Setu logo
- Mobile GPS permission support

## APK बनाना
GitHub Actions workflow पहले से शामिल है:
`.github/workflows/build-apk.yml`

GitHub में project upload करके:
**Actions → Build Sawari Setu APK → Run workflow**

Successful build के बाद artifact:
**sawari-setu-debug-apk**

उसमें `app-debug.apk` और उसका SHA-256 checksum मिलेगा.

## जरूरी सीमा
यह अभी prototype/demo है. Real production system के लिए backend/database, real-time driver matching, OTP/SMS, live GPS tracking, payments, secure authentication, production signing और Play Store publishing configuration जोड़नी होगी.
