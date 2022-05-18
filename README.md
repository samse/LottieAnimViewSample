# LottieAnimViewSample

Lottie is a library for Android, iOS, Web, and Windows that parses Adobe After Effects animations exported as json with Bodymovin and renders them natively on mobile and on the web! for more information. go to https://airbnb.io/lottie/#/README

Airbnb's Lottie load from Assets or url, not support downloaded contents.
LottieAnimationView2 supports downloaded contents with setAnimationFromLocal.

This document is only for Android

# load from Assets
```
        <com.airbnb.lottie.LottieAnimationView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_centerInParent="true"
            app:lottie_fileName="yoga_developer.json"
            app:lottie_autoPlay="true"
            app:lottie_loop="true"
            />
```

# load from url
```
        <com.airbnb.lottie.LottieAnimationView
            android:id="@+id/lottieView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_centerInParent="true"
            app:lottie_autoPlay="true"
            app:lottie_url="https://assets4.lottiefiles.com/private_files/lf30_1mukjnbu.json"
            app:lottie_loop="true"
            />
```


# load from downloaded file
 use LottieAnimationView2 that is customized LottieAnimationView

in xml
```
        <com.airbnb.lottie.LottieAnimationView2
            android:id="@+id/lottieView"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_centerInParent="true"
            app:lottie_autoPlay="true"
            app:lottie_loop="true"
            />
```
in code
```
        lottieView.setAnimationFromLocal(
            filePath,
            fileUrl,
            object : LottieAnimationView2.OnResultListener {
                override fun onResult(ret: Boolean, response: Any?) {
                }
            })
```


