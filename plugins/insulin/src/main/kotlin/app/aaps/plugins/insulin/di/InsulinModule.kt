package app.aaps.plugins.insulin.di

import app.aaps.core.interfaces.insulin.TsunamiIobEngine
import app.aaps.plugins.insulin.InsulinFragment
import app.aaps.plugins.insulin.tsunami.TsunamiIobEngineImpl
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector
import javax.inject.Singleton

@Module
@Suppress("unused")
abstract class InsulinModule {

    @ContributesAndroidInjector abstract fun contributesInsulinFragment(): InsulinFragment

    @Binds
    @Singleton
    abstract fun bindTsunamiIobEngine(impl: TsunamiIobEngineImpl): TsunamiIobEngine
}