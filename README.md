# MLBB CD Tracker (v4: hammasi panelda)

Ekranni o'qimaydi, o'yin fayllariga tegmaydi. Faqat overlay taymer.

## Build
Repoga yukla > Actions > "Build APK" > Artifacts > app-debug.apk

## Panel
Qator: `[Qahramon]  −  Lv n  +  [U]  [S]`
- **Qahramon tugmasi**: bos > ro'yxat ochiladi. Tepadagi qidiruv maydoniga nom yozsang ro'yxat filtrlanadi (masalan "gus" > Gusion). **Uzoq bos** > ult CD ni 3 level uchun +/- bilan kiritish (nom bilan telefonga saqlanadi, bir marta yetadi).
- **− / +**: qahramon leveli (1-15). Ult CD 4 / 8 / 12-levelda o'zgaradi. 4-levelgacha `U —`.
- **U**: ult taymeri. **S**: battle spell taymeri (bos = boshlash/bekor). **S ni uzoq bos** > spell tanlash.
- **CDR**: raqib CD kamayishi (0-40%). **✕**: to'xtatish.
- `U ✓60?` dagi **?** = CD noma'lum (taxminiy 60). Qahramonni uzoq bosib CD ni kirit.
- Ro'yxatda yo'q yangi qahramon: "Boshqa" ni tanla.

## Baza
- `assets/heroes.csv`: sening faylingning o'zi (MLBBDex, 2026-11-19). Qahramonlar ro'yxati shundan olinadi.
- `assets/heroes_patch.csv`: heroes.csv ustidan yoziladigan tuzatishlar (2-3 level qiymatlari va h.k.).
- `assets/spells.csv`: battle spell CDlari.
- Faylda faqat bitta CD bo'lsa, u 1-daraja qiymati deb olinadi va panelda `U ✓40~` ko'rinadi (`~` = keyingi levellarda kamroq bo'lishi mumkin).
- `?` = CD umuman noma'lum (taxminiy 60). `U —` = ult hali ochilmagan yoki qahramonda ult yo'q.
- Panelda uzoq bosib kiritgan CDlaring (telefonda saqlanadi) hamma narsadan ustun turadi.
