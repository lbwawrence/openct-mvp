package cc.metapro.openct.myclass;

/*
 *  Copyright 2016 - 2017 OpenCT open source class table
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.content.Context;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;
import android.widget.Toast;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.List;
import java.util.Map;

import cc.metapro.interactiveweb.utils.HTMLUtils;
import cc.metapro.openct.R;
import cc.metapro.openct.customviews.FormDialog;
import cc.metapro.openct.data.source.DBManger;
import cc.metapro.openct.data.source.Loader;
import cc.metapro.openct.data.university.CmsFactory;
import cc.metapro.openct.data.university.UniversityUtils;
import cc.metapro.openct.data.university.item.classinfo.Classes;
import cc.metapro.openct.utils.ActivityUtils;
import cc.metapro.openct.utils.Constants;
import cc.metapro.openct.utils.MyObserver;
import cc.metapro.openct.utils.webutils.TableUtils;
import cc.metapro.openct.widget.DailyClassWidget;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

@Keep
class ClassPresenter implements ClassContract.Presenter {

    private final String TAG = ClassPresenter.class.getSimpleName();
    private ClassContract.View mView;
    private Classes mEnrichedClasses;
    private Context mContext;

    ClassPresenter(@NonNull ClassContract.View view, Context context) {
        mView = view;
        mView.setPresenter(this);
        mContext = context;
    }

    @Override
    public Disposable loadOnlineInfo(final FragmentManager manager) {
        ActivityUtils.getProgressDialog(mContext, R.string.preparing_school_sys_info).show();

        Observable<Boolean> observable = Loader.prepareOnlineInfo(Loader.ACTION_CMS, mContext);

        Observer<Boolean> observer = new MyObserver<Boolean>(TAG) {
            @Override
            public void onNext(Boolean needCaptcha) {
                ActivityUtils.dismissProgressDialog();
                if (needCaptcha) {
                    ActivityUtils.showCaptchaDialog(manager, ClassPresenter.this);
                } else {
                    loadUserCenter(manager, "");
                }
            }

            @Override
            public void onError(Throwable e) {
                super.onError(e);
                ActivityUtils.showAdvCustomTip(mContext, Constants.TYPE_CLASS);
                Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };

        observable.observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);

        return null;
    }

    @Override
    public Disposable loadUserCenter(final FragmentManager manager, final String code) {
        ActivityUtils.getProgressDialog(mContext, R.string.login_to_system).show();

        Observable<Document> observable = Loader.login(Loader.ACTION_CMS, mContext, code);

        Observer<Document> observer = new MyObserver<Document>(TAG) {
            @Override
            public void onNext(final Document userCenterDom) {
                ActivityUtils.dismissProgressDialog();
                Constants.checkAdvCustomInfo(mContext);
                final List<String> urlPatterns = Constants.advCustomInfo.getClassUrlPatterns();
                if (!urlPatterns.isEmpty()) {
                    if (urlPatterns.size() == 1) {
                        // fetch first page from user center, it will find the class info page in most case
                        Element target = HTMLUtils.getElementSimilar(userCenterDom, Jsoup.parse(urlPatterns.get(0)).body().children().first());
                        if (target != null) {
                            loadTargetPage(manager, target.absUrl("href"));
                        } else {
                            ActivityUtils.showLinkSelectionDialog(manager, Constants.TYPE_CLASS, userCenterDom, ClassPresenter.this);
                        }
                    } else if (urlPatterns.size() > 1) {
                        // fetch more page to reach class info page, especially in QZ Data Soft CMS System
                        Observable<String> extraObservable = Observable.create(new ObservableOnSubscribe<String>() {
                            @Override
                            public void subscribe(ObservableEmitter<String> e) throws Exception {
                                CmsFactory factory = Loader.getCms(mContext);
                                Document lastDom = userCenterDom;
                                Element finalTarget = null;
                                for (String pattern : urlPatterns) {
                                    if (lastDom != null) {
                                        finalTarget = HTMLUtils.getElementSimilar(lastDom, Jsoup.parse(pattern).body().children().first());
                                    }
                                    if (finalTarget != null) {
                                        lastDom = factory.getPageDom(finalTarget.absUrl("href"));
                                    }
                                }
                                if (finalTarget != null) {
                                    e.onNext(finalTarget.absUrl("href"));
                                } else {
                                    e.onError(new Exception("failed"));
                                }
                            }
                        });

                        Observer<String> extraObserver = new MyObserver<String>(TAG) {
                            @Override
                            public void onNext(String targetUrl) {
                                loadTargetPage(manager, targetUrl);
                            }

                            @Override
                            public void onError(Throwable e) {
                                super.onError(e);
                                Toast.makeText(mContext, "根据之前的设置未能获取到预期的页面\n\n需要重新执行", Toast.LENGTH_LONG).show();
                                ActivityUtils.showLinkSelectionDialog(manager, Constants.TYPE_CLASS, userCenterDom, ClassPresenter.this);
                            }
                        };

                        extraObservable.subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(extraObserver);
                    } else {
                        ActivityUtils.showLinkSelectionDialog(manager, Constants.TYPE_CLASS, userCenterDom, ClassPresenter.this);
                    }
                } else {
                    ActivityUtils.showLinkSelectionDialog(manager, Constants.TYPE_CLASS, userCenterDom, ClassPresenter.this);
                }
            }

            @Override
            public void onError(Throwable e) {
                super.onError(e);
                ActivityUtils.showAdvCustomTip(mContext, Constants.TYPE_CLASS);
                Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };

        observable.observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);

        return null;
    }

    @Override
    public Disposable loadTargetPage(final FragmentManager manager, final String url) {
        ActivityUtils.getProgressDialog(mContext, R.string.loading_class_page).show();

        Observable<Document> observable = Observable.create(new ObservableOnSubscribe<Document>() {
            @Override
            public void subscribe(ObservableEmitter<Document> e) throws Exception {
                e.onNext(Loader.getCms(mContext).getPageDom(url));
            }
        });

        Observer<Document> observer = new MyObserver<Document>(TAG) {
            @Override
            public void onNext(Document document) {
                ActivityUtils.dismissProgressDialog();
                FormDialog.newInstance(document, ClassPresenter.this).show(manager, "form_dialog");
            }

            @Override
            public void onError(Throwable e) {
                super.onError(e);
                ActivityUtils.showAdvCustomTip(mContext, Constants.TYPE_CLASS);
                Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };

        observable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);

        return null;
    }

    @Override
    public Disposable loadQuery(final FragmentManager manager, final String actionURL, final Map<String, String> queryMap, final boolean needNewPage) {
        ActivityUtils.getProgressDialog(mContext, R.string.loading_classes).show();
        Observable<Document> observable = Observable.create(new ObservableOnSubscribe<Document>() {
            @Override
            public void subscribe(ObservableEmitter<Document> e) throws Exception {
                e.onNext(Loader.getCms(mContext).queryClassPageDom(actionURL, queryMap, needNewPage));
            }
        });

        Observer<Document> observer = new MyObserver<Document>(TAG) {
            @Override
            public void onNext(Document document) {
                ActivityUtils.dismissProgressDialog();
                Constants.checkAdvCustomInfo(mContext);
                String tableId = Constants.advCustomInfo.mClassTableInfo.mClassTableID;
                if (TextUtils.isEmpty(tableId)) {
                    ActivityUtils.showTableChooseDialog(manager, Constants.TYPE_CLASS, document, ClassPresenter.this);
                } else {
                    Map<String, Element> map = TableUtils.getTablesFromTargetPage(document);
                    List<Element> rawClasses = UniversityUtils.getRawClasses(map.get(tableId), mContext);
                    mEnrichedClasses = UniversityUtils.generateClasses(mContext, rawClasses, Constants.advCustomInfo.mClassTableInfo);
                    if (mEnrichedClasses.size() == 0) {
                        Toast.makeText(mContext, R.string.classes_empty, Toast.LENGTH_LONG).show();
                    } else {
                        storeClasses();
                        loadLocalClasses();
                        Toast.makeText(mContext, R.string.load_online_classes_successful, Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onError(Throwable e) {
                super.onError(e);
                ActivityUtils.showAdvCustomTip(mContext, Constants.TYPE_CLASS);
                Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };

        observable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);

        return null;
    }

    @Override
    public void loadLocalClasses() {
        Observable<Classes> observable = Loader.getClasses(mContext);

        Observer<Classes> observer = new MyObserver<Classes>(TAG) {
            @Override
            public void onNext(Classes enrichedClasses) {
                mEnrichedClasses = enrichedClasses;
                try {
                    mView.updateClasses(mEnrichedClasses);
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }

            @Override
            public void onError(Throwable e) {
                super.onError(e);
                Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };

        observable.observeOn(AndroidSchedulers.mainThread())
                .subscribe(observer);
    }

    private void storeClasses() {
        try {
            DBManger manger = DBManger.getInstance(mContext);
            manger.updateClasses(mEnrichedClasses);
            DailyClassWidget.update(mContext);
        } catch (Exception e) {
            Toast.makeText(mContext, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void start() {
        loadLocalClasses();
    }
}
