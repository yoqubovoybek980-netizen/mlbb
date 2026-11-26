# MLBB CD Tracker (2-qadam: ekranni o'qish)

## GitHub'da build qilish
1. Hamma fayllarni yangi GitHub repoga yukla.
2. **Actions** > "Build APK" (yoki Run workflow).
3. Tugagach **Artifacts** > `mlbb-cd-tracker-apk` ni yuklab ol, ichidagi `app-debug.apk` ni o'rnat.

## Ishlatish
1. Ilovani och: "boshqa ilovalar ustida chizish" va "ekranni yozish" ruxsatlarini ber.
2. MLBB'ni och. O'yin boshlanganda scoreboard'ni och (hamma spell/ult tayyor paytda).
3. Panelni (☰ CD ni surib) ikonkalarni to'smaydigan joyga qo'y.
4. **Kalib** bos: 10 ta katak chiqadi (1F = 1-raqib spelli, 1U = 1-raqib ulti ...). Ularni ikonkalar ustiga surib qo'y.
5. **Saqla** bos: kataklar yo'qoladi va "Baza" (tayyor holatdagi yorqinlik) olinadi.
6. **Auto: ON** bos. Ikonka bazaning 65% dan pastga tushsa (xira = CDda), taymer o'zi boshlanadi.
7. Pastdagi raqamlar (F92 U88 ...) = har ikonkaning bazaga nisbati, %. CDda bo'lganda ~30-50% atrofiga tushishi kerak.
   Agar tushmasa, `OverlayService.kt` dagi `DIM_RATIO` ni o'zgartir.

Qo'lda taymer ham ishlaydi: F/U ni bos, yana bossang bekor. U ni uzoq bos: ult CD almashadi. CDR: raqib CD kamayishi.

## Cheklovlar
- Faqat scoreboard ochiq paytda ikonkalar ko'rinadi. Yopiq bo'lsa, taymer ochgan paytingdan boshlanadi.
- Har o'yinda Kalib/Saqla'ni qayta qil (baza yangilanishi uchun).
- Moonton uchinchi tomon ilovalarni qoidabuzarlik deb hisoblashi mumkin. Ikkinchi akkauntda sina.
