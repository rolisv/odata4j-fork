package org.odata4j.examples.jersey.consumer;

import org.odata4j.consumer.ODataConsumer;
import org.odata4j.examples.consumers.AbstractEBayConsumerExample;
import org.odata4j.jersey.consumer.ODataJerseyConsumer;

public class EBayJerseyConsumerExample extends AbstractEBayConsumerExample {

  public static void main(String[] args) {
    EBayJerseyConsumerExample example = new EBayJerseyConsumerExample();
    example.run(args);
  }

  @Override
  public ODataConsumer create(String endpointUri) {
    return ODataJerseyConsumer.create(endpointUri);
  }
}