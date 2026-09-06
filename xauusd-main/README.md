# JForex Tick Exporter

دانلود تیک‌های تاریخی Dukascopy با **JForex SDK** و انتشار خودکار به صورت فایل زیپ در **GitHub Releases**.

منوی اجرای دستی (workflow_dispatch) دقیقاً مشابه فرمی است که درخواست کردید:

- **Symbol** (مثلاً `EURUSD`, `XAUUSD`)
- **Start date/time UTC**
- **End date/time UTC (exclusive)**
- گزینه ساخت Release

## محدودیت‌های مهم

| مورد | توضیح |
|------|--------|
| حجم داده | یک سال کامل تیک EURUSD می‌تواند چند گیگابایت باشد |
| زمان اجرا | جاب حداکثر ۶ ساعت تنظیم شده (قابل تغییر) |
| حافظه | `-Xmx4g` استفاده می‌شود |
| حساب | فقط **Demo** توصیه می‌شود |
| Release | محدودیت حجم فایل در GitHub Releases را در نظر بگیرید |

برای بازه‌های خیلی بزرگ، بازه را به چند بخش سالیانه یا فصلی تقسیم کنید.

## راه‌اندازی سریع

### ۱. ساخت ریپازیتوری

محتوای این پوشه را در یک ریپو روی GitHub قرار دهید.

### ۲. Secrets

در Settings → Secrets and variables → Actions این دو secret را اضافه کنید:

| Secret | مقدار |
|--------|--------|
| `DUKASCOPY_USERNAME` | یوزرنیم حساب دمو |
| `DUKASCOPY_PASSWORD` | پسورد حساب دمو |

### ۳. اجرای workflow

1. بروید به تب **Actions**
2. workflow به نام **Export Ticks** را انتخاب کنید
3. **Run workflow** را بزنید
4. فیلدها را پر کنید:
   - Symbol: `EURUSD`
   - Start: `2023-01-01`
   - End: `2024-01-01` (exclusive)
5. Run

بعد از اتمام:

- فایل زیپ به عنوان **Artifact** آپلود می‌شود
- اگر گزینه Release فعال باشد، یک Release جدید با همان فایل ساخته می‌شود

## اجرای محلی (تست)

```bash
export DUKASCOPY_USERNAME=your_demo_user
export DUKASCOPY_PASSWORD=your_demo_pass

mvn -q clean package -DskipTests

java -Xmx2g \
  -Dinstrument=EURUSD \
  -Dfrom="2024-01-01" \
  -Dto="2024-01-03" \
  -jar target/jforex-tick-exporter-1.0.0.jar
```

یا با environment variables:

```bash
INSTRUMENT=EURUSD FROM=2024-01-01 TO=2024-01-03 \
  java -Xmx2g -jar target/jforex-tick-exporter-1.0.0.jar
```

## فرمت خروجی CSV

```
GmtTime,Bid,Ask,BidVolume,AskVolume
2014-12-01 00:00:00.042,0.77919,0.77939,1.56,2.51
...
```

زمان‌ها همیشه **UTC/GMT** هستند.

## نکات فنی

- از `history.getTicks()` به صورت چانک‌های ۶ ساعته استفاده می‌شود.
- تیک‌های تکراری مرز چانک‌ها حذف می‌شوند.
- انتهای بازه (`to`) exclusive است.
- برای LIVE به جای DEMO باید JNLP و احتمالاً مدیریت PIN تغییر کند (در این پروژه پشتیبانی نشده).

## هشدار

دانلود حجم بسیار بالا ممکن است باعث محدودیت از سمت Dukascopy یا timeout در Actions شود.  
همیشه ابتدا با بازه کوتاه (چند روز) تست کنید.
