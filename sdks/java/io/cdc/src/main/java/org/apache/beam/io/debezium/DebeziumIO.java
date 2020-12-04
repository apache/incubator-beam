package org.apache.beam.io.debezium;


import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;

import org.apache.kafka.connect.source.SourceConnector;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auto.value.AutoValue;

public class DebeziumIO {
	
	private static final Logger LOG = LoggerFactory.getLogger(DebeziumIO.class);
	
	/**
	 * Read data from a Debezium source.
	 *
	 * @param <T> Type of the data to be read.
	*/
	public static <T> Read<T> read(){
		return new AutoValue_DebeziumIO_Read.Builder<T>().build();
	}
	
	public static <ParameterT, OutputT> ReadAll<ParameterT, OutputT> readAll() {
		return new AutoValue_DebeziumIO_ReadAll.Builder<ParameterT, OutputT>().build();
	}
	
	/** Disallow construction of utility class. */
	private DebeziumIO() {}
	
	/** Implementation of {@link #read}. */
	@AutoValue
	public abstract static class Read<T> extends PTransform<PBegin, PCollection<T>> {

		abstract @Nullable SerializableFunction<Void, SourceConnector> getConnectorProviderFn();
		 
		abstract @Nullable Coder<T> getCoder();

		abstract Builder<T> toBuilder();
		  
		@AutoValue.Builder
		abstract static class Builder<T> {
			
			abstract Builder<T> setConnectorProviderFn(SerializableFunction<Void, SourceConnector> dataSourceProviderFn);
			
			abstract Builder<T> setCoder(Coder<T> coder);
			
			abstract Read<T> build();
			  
		}
		  
		public Read<T> withConnectorConfiguration(final ConnectorConfiguration config) {
			return withConnectorProviderFn(null);
		}
		  
		public Read<T> withConnectorProviderFn(SerializableFunction<Void, SourceConnector> connectorProviderFn) {
			return toBuilder().setConnectorProviderFn(connectorProviderFn).build();
		}
		  
		public Read<T> withCoder(Coder<T> coder) {
			checkArgument(coder != null, "coder can not be null");
		    return toBuilder().setCoder(coder).build();
		}
		
		@Override
		public PCollection<T> expand(PBegin input){
			LOG.info("Hello world from log.");
			
			//return input.apply("Create", Create.of((Void) null)).apply("ReadAll", readAll());
			return (PCollection<T>) input.apply("Read", Create.ofProvider(ValueProvider.StaticValueProvider.of("sd"), StringUtf8Coder.of()));
		}
		  
	}
	/** Implementation of {@link #readAll}. */
	@AutoValue
	public abstract static class ReadAll<ParameterT, OutputT>
		extends PTransform<PCollection<ParameterT>, PCollection<OutputT>> {
		@AutoValue.Builder
		abstract static class Builder<ParameterT, OutputT> {
			abstract ReadAll<ParameterT, OutputT> build();
		}
		@Override
		public PCollection<OutputT> expand(PCollection<ParameterT> input) {
			PCollection<OutputT> output = input.apply("Read", ParDo.of(new ReadFn<>()));
			return output;
		}
	}
	
	private static class ReadFn<ParameterT, OutputT> extends DoFn<ParameterT, OutputT> {

		@ProcessElement
		public void processElement(ProcessContext context) throws Exception {
			
			LOG.info("Helloa....");
		}
	}
	
	
	/**
	 * A POJO describing a Debezium configuration.
	*/
	@AutoValue
	public abstract static class ConnectorConfiguration implements Serializable {
		  
		abstract @Nullable ValueProvider<Class<?>> getConnectorClass();

		abstract @Nullable ValueProvider<String> getHostName();
		
		abstract @Nullable ValueProvider<String> getPort();
		
		abstract @Nullable ValueProvider<String> getUsername();
		
		abstract @Nullable ValueProvider<String> getPassword();
		
		abstract @Nullable ValueProvider<String> getConnectionProperties();
		
		abstract Builder builder();
		
		@AutoValue.Builder
		abstract static class Builder {
			
			abstract Builder setConnectorClass(ValueProvider<Class<?>> connectorClass);
			
			abstract Builder setHostName(ValueProvider<String> hostname);
			
			abstract Builder setPort(ValueProvider<String> port);
			
			abstract Builder setUsername(ValueProvider<String> username);
			
			abstract Builder setPassword(ValueProvider<String> password);
			
			abstract Builder setConnectionProperties(ValueProvider<String> connectionProperties);

			abstract ConnectorConfiguration build();
		      
		}
		
		public static ConnectorConfiguration create(Class<?> connectorClass, String hostname) {
			checkArgument(connectorClass != null, "connectorClass can not be null");
		    checkArgument(hostname != null, "hostname can not be null");
		    return create(
		    		ValueProvider.StaticValueProvider.of(connectorClass),
		            ValueProvider.StaticValueProvider.of(hostname)); 
		}
		
		public static ConnectorConfiguration create(
				ValueProvider<Class<?>> connectorClass, ValueProvider<String> hostname)
		{
			checkArgument(connectorClass != null, "connectorClass can not be null");
		    checkArgument(hostname != null, "hostname can not be null");
		    return new AutoValue_DebeziumIO_ConnectorConfiguration
		    		.Builder()
		    		.setConnectorClass(connectorClass)
		    		.setHostName(hostname)
		    		.build();
		}
		
		//ConnectorClass
		public ConnectorConfiguration withConnectorClass(Class<?> connectorClass) {
			return withConnectorClass(ValueProvider.StaticValueProvider.of(connectorClass));
		}
		
		public ConnectorConfiguration withConnectorClass(ValueProvider<Class<?>> connectorClass) {
			return builder().setConnectorClass(connectorClass).build();
		}
		
		//HostName
		public ConnectorConfiguration withHostName(String hostName) {
			return withHostName(ValueProvider.StaticValueProvider.of(hostName));
		}
		
		public ConnectorConfiguration withHostName(ValueProvider<String> hostName) {
			return builder().setHostName(hostName).build();
		}
		
		//Port
		public ConnectorConfiguration withPort(String port) {
			return withPort(ValueProvider.StaticValueProvider.of(port));
		}
		
		public ConnectorConfiguration withPort(ValueProvider<String> port) {
			return builder().setPort(port).build();
		}
		
		//Username
		public ConnectorConfiguration withUsername(String username) {
	      return withUsername(ValueProvider.StaticValueProvider.of(username));
	    }

	    public ConnectorConfiguration withUsername(ValueProvider<String> username) {
	      return builder().setUsername(username).build();
	    }

	    //Password
	    public ConnectorConfiguration withPassword(String password) {
	      return withPassword(ValueProvider.StaticValueProvider.of(password));
	    }

	    public ConnectorConfiguration withPassword(ValueProvider<String> password) {
	      return builder().setPassword(password).build();
	    }
	    
	    //ConnectionProperties
	    public ConnectorConfiguration withConnectionProperties(String connectionProperties) {
	      return withConnectionProperties(ValueProvider.StaticValueProvider.of(connectionProperties));
	    }

	    public ConnectorConfiguration withConnectionProperties(ValueProvider<String> connectionProperties) {
	      return builder().setConnectionProperties(connectionProperties).build();
	    }
		
	}
	
}
