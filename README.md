# マスク判別アプリケーション
機械学習を用いてマスクの着用有無判別するAndroidスマートフォンアプリケーションです．

# アプリケーションの処理手順
本アプリケーションの処理手順は以下のようになっています．顔検出にはfirebaseの顔検出APIを，マスク着用有無の判別には，[次のリンク](https://www.pyimagesearch.com/2020/05/04/covid-19-face-mask-detector-with-opencv-keras-tensorflow-and-deep-learning/)で紹介されていた機械学習モデル(tfliteモデル)を使用しました．
こちらでは，スマートフォンでも機械学習による推論を可能にするためTensorFloeLiteを用いて機械学習モデルを最適化しています．

![処理手順](https://user-images.githubusercontent.com/81143699/114265393-86c10500-9a2b-11eb-9511-bff2b2f15725.PNG)
